package com.classsche.mobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uses the already authenticated teaching session; never submits credentials or starts another login. */
internal class ExamQueryClient(
  private val logger: (String, String, String) -> Unit,
  private val cookies: LoginCookieStore = WebViewLoginCookieStore,
  private val queryUrl: String = UniversityEndpoints.EXAM_QUERY,
  private val listUrl: String = UniversityEndpoints.EXAM_LIST
) {
  fun fetch(courses: List<TimetableCourse> = emptyList()): List<ExamArrangement> {
    val query = Jsoup.parse(ByteArrayInputStream(request(queryUrl, "GET", null, UniversityEndpoints.TIMETABLE)), null, queryUrl)
    val form = query.selectFirst("form[name=ksapQueryForm]")
      ?: error("考试查询解析错误：未找到 ksapQueryForm，登录状态可能已失效，原考试缓存已保留")
    val method = form.attr("method").ifBlank { "post" }.uppercase()
    check(method in listOf("GET", "POST")) { "考试查询解析错误：不支持的表单请求方式" }
    // Submit only to the known exam-list endpoint, even if the school changes the form action.
    val values = parameters(form)
    val bytes = request(listUrl, method, values, queryUrl)
    return ExamParser.parseBytes(bytes, listUrl, courses)
  }

  private fun parameters(form: Element): Map<String, String> = linkedMapOf<String, String>().apply {
    form.select("input[name],textarea[name],select[name]").forEach { field ->
      val name = field.attr("name").trim()
      if (name.isBlank() || field.hasAttr("disabled")) return@forEach
      when (field.tagName()) {
        "select" -> {
          val options = field.select("option:not([disabled])")
          put(name, (options.firstOrNull { it.hasAttr("selected") && it.attr("value").isNotBlank() }
            ?: options.firstOrNull { it.attr("value").isNotBlank() })?.attr("value").orEmpty())
        }
        "textarea" -> put(name, field.text())
        else -> when (field.attr("type").lowercase()) {
          "submit", "button", "file", "image", "reset" -> Unit
          "checkbox", "radio" -> if (field.hasAttr("checked")) put(name, field.attr("value").ifBlank { "on" })
          else -> put(name, field.attr("value"))
        }
      }
    }
    check(!get("xnxqid").isNullOrBlank()) { "考试查询解析错误：未取得校方当前学期，不使用过期默认学期" }
  }

  private fun request(url: String, method: String, values: Map<String, String>?, referer: String): ByteArray {
    val encoded = values?.entries?.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }.orEmpty()
    val target = URL(if (method == "GET" && encoded.isNotEmpty()) url + (if (url.contains('?')) "&" else "?") + encoded else url)
    val address = target.host + target.path
    val step = if (url == queryUrl) "考试查询页" else "考试列表页"
    val connection = target.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.instanceFollowRedirects = false
      connection.useCaches = false
      connection.connectTimeout = 10000; connection.readTimeout = 10000
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36")
      connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
      connection.setRequestProperty("Referer", referer)
      cookies.read(target.toString()).takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
      if (method == "POST") {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
      }
      val code = connection.responseCode
      cookies.receive(target.toString(), connection.headerFields.filterKeys { it != null })
      logger("EXAM_FETCH", "INFO", "$step HTTP $code，$address")
      check(code !in 300..399) { "目标网站跳转错误：$step HTTP $code，$address，教务会话可能已失效，原缓存已保留" }
      check(code in 200..299) { "网站返回错误：$step HTTP $code，$address，原缓存已保留" }
      return connection.inputStream.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer); if (count < 0) break
          check(output.size() + count <= 8 * 1024 * 1024) { "考试页面解析错误：响应内容过大" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
    } catch (error: Exception) {
      throw IllegalStateException("$step：${FailureDetails.describe(error)}（$address）", error)
    } finally {
      connection.disconnect()
      cookies.flush()
    }
  }
}
