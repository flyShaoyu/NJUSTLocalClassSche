package com.classsche.mobile

import org.jsoup.nodes.Document
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal interface EvaluationTransport {
  fun get(url: String): Document
  fun post(url: String, fields: List<Pair<String, String>>, charset: String): Document
}
internal interface EvaluationJournal {
  fun uncertain(key: String): Boolean
  /** Must persist synchronously before any POST is sent. */
  fun markUncertain(key: String)
  fun confirmed(key: String)
}

/** One explicit, consumed preview authorizes one sequence of POSTs; a failed POST is never retried. */
internal class TeachingEvaluationEngine(
  private val http: EvaluationTransport,
  private val journal: EvaluationJournal,
  private val ensureSession: () -> Unit,
  private val sessionKey: () -> String = { "fixture" },
  private val now: () -> Long = System::currentTimeMillis
) {
  companion object { private val busy = AtomicBoolean(false) }
  data class Prepared(val course: TeachingEvaluationParser.Course, val fingerprint: String, val choices: List<String>)
  data class Plan(val id: String, val courses: List<Prepared>, val settings: TeachingEvaluationParser.Settings,
    val notices: List<String>, val createdAt: Long, val session: String)
  data class Item(val title: String, val state: String, val message: String, val url: String)
  data class Progress(val stage: String, val done: Int, val total: Int, val title: String = "", val items: List<Item> = emptyList())
  data class Outcome(val items: List<Item>, val stopped: Boolean, val notices: List<String>)
  @Volatile private var cancelled = false
  @Volatile private var plan: Plan? = null
  fun cancel() { cancelled = true }
  private fun checkCancelled() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("操作已停止，尚未开始的课程没有提交") }
  private fun <T> exclusive(block: () -> T): T {
    check(busy.compareAndSet(false, true)) { "评教操作正在进行，请等待当前操作结束" }
    cancelled = false
    try { return block() } finally { busy.set(false) }
  }

  fun scan(settings: TeachingEvaluationParser.Settings, progress: (Progress) -> Unit = {}): Plan = exclusive {
    plan = null
    ensureSession()
    val pages = ArrayDeque<String>().apply { add(TeachingEvaluationParser.ENTRY) }
    val visited = mutableSetOf<String>()
    val pending = linkedMapOf<String, TeachingEvaluationParser.Course>()
    val done = mutableSetOf<String>()
    val notices = mutableListOf<String>()
    while (pages.isNotEmpty()) {
      checkCancelled()
      val url = pages.removeFirst()
      if (!visited.add(url)) continue
      check(visited.size <= 60) { "评教列表页数过多，请在学校网页缩小查询范围" }
      progress(Progress("正在读取评教列表", visited.size, visited.size + pages.size))
      val list = TeachingEvaluationParser.listing(http.get(url))
      list.courses.forEach { course ->
        if (course.completed) { journal.confirmed(course.key); pending.remove(course.key); done.add(course.key) }
        else if(course.key !in done)pending.putIfAbsent(course.key, course)
      }
      list.children.filter { it !in visited && it !in pages }.forEach { pages.add(it) }
      check(pending.size <= 300) { "待评课程超过 300 门，请分批在学校网页处理" }
    }
    val courses = mutableListOf<Prepared>()
    pending.values.forEachIndexed { index, course ->
      checkCancelled()
      progress(Progress("正在读取评级选项", index, pending.size, course.title))
      if (journal.uncertain(course.key)) {
        notices.add("${course.title}：上次提交结果待核实，本次不会重复提交；请在学校网页检查")
      } else {
        try {
          val form = TeachingEvaluationParser.form(http.get(course.url), settings)
          courses.add(Prepared(course, form.fingerprint, form.choices))
        } catch (error: Exception) {
          if (error is CancellationException || error is CampusLoginRequired) throw error
          notices.add("${course.title}：${FailureDetails.describe(error)}")
        }
      }
    }
    checkCancelled()
    Plan(UUID.randomUUID().toString(), courses, settings, notices, now(), sessionKey()).also { plan = it }
  }

  fun submit(id: String, progress: (Progress) -> Unit = {}): Outcome = exclusive {
    val current = plan ?: error("没有可提交的预览，请先读取待评课程")
    check(current.id == id && now() - current.createdAt in 0..600000) { "评教预览已过期，请重新读取课程" }
    check(current.session == sessionKey()) { "登录会话已变化，请重新读取课程后确认" }
    plan = null // Consume before the first request. Replaying a click cannot resend a form.
    val results = mutableListOf<Item>()
    var stopped = false
    val notices = current.notices.toMutableList()
    for ((index, prepared) in current.courses.withIndex()) {
      val course = prepared.course
      var dispatched = false
      try {
        checkCancelled()
        check(current.session == sessionKey()) { "登录会话已变化，停止提交后续课程" }
        progress(Progress("正在检查课程状态", index, current.courses.size, course.title, results.toList()))
        val before = TeachingEvaluationParser.listing(http.get(course.listUrl)).courses.find { it.key == course.key }
          ?: error("课程状态解析错误：原列表中已找不到该课程，未发送评价")
        if (before.completed) {
          journal.confirmed(course.key)
          results.add(Item(course.title, "skipped", "校方已标记完成，已跳过", course.listUrl))
          continue
        }
        check(!journal.uncertain(course.key)) { "上次提交结果待核实，不能自动重试" }
        val form = TeachingEvaluationParser.form(http.get(course.url), current.settings)
        check(form.fingerprint == prepared.fingerprint && form.choices == prepared.choices) { "评价表结构或选项已变化，未发送评价，请重新预览" }
        check(current.session == sessionKey()) { "登录会话已变化，未发送评价" }
        checkCancelled()
        journal.markUncertain(course.key)
        dispatched = true
        progress(Progress("正在提交评价", index, current.courses.size, course.title, results.toList()))
        http.post(form.action, form.fields, form.charset)
        // A successful HTTP status or an alert string alone is not proof of completion.
        var verified = false
        repeat(3) { attempt ->
          if (!verified) {
            if (attempt > 0) Thread.sleep(700)
            verified = TeachingEvaluationParser.listing(http.get(course.listUrl)).courses.any { it.key == course.key && it.completed }
          }
        }
        check(verified) { "校方尚未标记完成，提交结果待核实；不会自动重试，请在学校网页检查" }
        journal.confirmed(course.key)
        results.add(Item(course.title, "success", "校方已确认完成", course.listUrl))
        progress(Progress("已确认课程评价", index + 1, current.courses.size, course.title, results.toList()))
      } catch (error: Exception) {
        val message = if (dispatched) "${FailureDetails.describe(error)}；本次请求可能已被校方接收，已停止后续课程" else FailureDetails.describe(error)
        results.add(Item(course.title, if (dispatched) "uncertain" else if (error is CancellationException) "stopped" else "failed", message, course.listUrl))
        stopped = true
        break
      }
    }
    if (!stopped) {
      for (url in results.filter { it.state in listOf("success", "skipped") }.map { it.url }.distinct()) {
        try {
          if (TeachingEvaluationParser.listing(http.get(url)).hasBatchConfirmation) notices.add("校方页面仍显示批次确认按钮，请打开学校网页核对批次状态：课程评价结果已逐门核实")
        } catch (_: Exception) { notices.add("课程评价已核实，但批次状态刷新失败，请打开学校网页检查") }
      }
    }
    Outcome(results, stopped || cancelled, notices.distinct())
  }
}
