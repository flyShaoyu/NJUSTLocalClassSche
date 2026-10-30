package com.classsche.mobile

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest

internal object TeachingEvaluationParser {
  const val ENTRY = "https://bkjw.njust.edu.cn/njlgdx/xspj/xspj_find.do"
  data class Course(val key: String, val title: String, val url: String, val listUrl: String, val completed: Boolean)
  data class Listing(val courses: List<Course>, val children: List<String>, val hasBatchConfirmation: Boolean)
  data class Form(val action: String, val fields: List<Pair<String, String>>, val fingerprint: String, val choices: List<String>, val charset: String)
  data class Settings(val option: Int = 0, val lastOption: Int = 1, val comment: String = "")

  fun allowed(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "bkjw.njust.edu.cn" && uri.port in listOf(-1, 443) && uri.userInfo == null &&
      uri.path.startsWith("/njlgdx/xspj/") && !uri.path.contains("..")
  }.getOrDefault(false)

  fun resolve(base: String, value: String): String {
    val absolute = URI(base).resolve(value.trim()).toString().replaceFirst("http://bkjw.njust.edu.cn/", "https://bkjw.njust.edu.cn/").substringBefore('#')
    require(allowed(absolute)) { "目标网站跳转错误：评教链接不属于当前教务评教页面" }
    return absolute
  }

  private fun link(base: String, element: Element): String {
    val href = element.attr("href")
    if (!href.startsWith("javascript:", true) && href.isNotBlank() && href != "#") return resolve(base, href)
    val literal = Regex("[\"']([^\"']*xspj_[^\"']*\\.do[^\"']*)[\"']").find(href + " " + element.attr("onclick"))?.groupValues?.get(1)
      ?: error("评教页面解析错误：课程链接使用了无法识别的跳转方式")
    return resolve(base, literal)
  }

  fun key(url: String): String {
    val uri = URI(url)
    val path = uri.path.replace(Regex("xspj_(?:view|show)\\.do"), "xspj_edit.do")
    val query = uri.rawQuery.orEmpty().split('&').filter { part ->
      val name=part.substringBefore('=').lowercase()
      part.isNotBlank() && name !in setOf("isview","isedit","_","timestamp","t","_t","execution","lt","ticket","jsessionid","sessionid") &&
        !name.contains("token") && !name.contains("csrf") && !name.contains("nonce")
    }.sorted().joinToString("&")
    return hash(path + "?" + query)
  }

  private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
  private fun text(element: Element) = element.text().replace(Regex("\\s+"), " ").trim()
  private fun completed(row: Element): Boolean {
    fun statusLabel(value: String): Boolean {
      val label=value.replace(Regex("\\s+"),"")
      return label.startsWith("是否") && (label.contains("完成") || label.contains("已评")) || label.endsWith("状态")
    }
    val headerRow=row.closest("table")?.select("tr")?.firstOrNull { candidate -> candidate.children().any { statusLabel(it.text()) } }
    val headers=headerRow?.children()
    val cells=row.children()
    if(headers!=null)for(index in 0 until minOf(headers.size,cells.size)) {
      val label=headers[index].text().replace(Regex("\\s+"),"")
      if((label.startsWith("是否") || label.endsWith("状态")) && cells[index].text().trim() in listOf("是","完成","已完成","已评价","已评教","已评","已提交"))return true
    }
    return cells.any { cell ->
      cell.select("input,select,script").isEmpty() && Regex("^(?:评价状态[:：]\\s*)?(?:已评价|已评教|已完成|评价完成|评教完成|已提交|完成|已评)$").matches(cell.text().trim())
    }
  }
  fun checkPage(document: Document) {
    require(allowed(document.location())) { "目标网站跳转错误：未在教务评教页面" }
    if(document.selectFirst("input[type=password]")!=null)throw CampusLoginRequired("登录状态已失效，请先在登录页完成认证")
  }

  fun listing(document: Document): Listing {
    checkPage(document)
    val courses = document.select("a[href*=xspj_edit],a[href*=xspj_view],a[href*=xspj_show],a[onclick*=xspj_edit]").map { anchor ->
      val url = link(document.location(), anchor)
      check(Regex(".*/xspj_(?:edit|view|show)\\.do").matches(URI(url).path)) { "评教页面解析错误：课程链接不是预期的评价表" }
      val row = anchor.closest("tr") ?: error("评教页面解析错误：课程缺少对应表格行")
      val cells = row.children().toList().filter { it.tagName() in listOf("td", "th") }
      val title = cells.map { text(it) }.filter { it.isNotBlank() && !it.matches(Regex("\\d+")) && !Regex("^(?:评价|评教|查看|未评|已评|未完成|已完成|操作|提交)$").matches(it) }
        .take(4).joinToString(" · ").take(240).ifBlank { "待评课程" }
      Course(key(url), title, url, document.location(), completed(row) || text(anchor).contains("查看"))
    }.distinctBy { it.key }
    val children = document.select("a[title=点击进入评价],a[href*=xspj_list],a[href*=xspj_find]").filter { anchor ->
      !anchor.hasAttr("disabled") && anchor.closest("tr")?.let { !completed(it) } != false &&
        (anchor.attr("title") == "点击进入评价" || Regex("下一页|下页|末页|^\\d+$").containsMatchIn(text(anchor)))
    }.map { link(document.location(), it) }.filter { it != document.location() }.distinct()
    check(courses.isNotEmpty() || children.isNotEmpty() || (document.text().contains("评教") && document.select("table").isNotEmpty()) ||
      Regex("暂无.*评教|没有.*评教|无可用.*批次|评教.*未开放|无数据").containsMatchIn(document.text())) {
      "评教页面解析错误：未找到评教批次、课程列表或明确的空数据提示"
    }
    return Listing(courses, children, document.getElementById("btnShenshen") != null)
  }

  fun form(document: Document, settings: Settings): Form {
    checkPage(document)
    require(settings.option in 0..4 && settings.lastOption in 0..4 && settings.comment.length <= 2000) { "评级设置无效" }
    val form = document.selectFirst("form#Form1") ?: error("评价表解析错误：缺少 Form1 表单")
    check(form.attr("method").equals("post", true)) { "评价表解析错误：提交方法不是 POST" }
    val action = resolve(document.location(), form.attr("action").ifBlank { document.location() })
    check(form.selectFirst("input[type=file],input[type=password]") == null) { "评价表包含不支持的输入项，请在学校网页填写" }
    check(form.selectFirst("input[name=yzm],input[name=verifyCode],input[name=captcha],img[src*=captcha]") == null) { "本门评价需要验证码，请在学校网页手动完成，不会自动提交" }
    fun disabled(input: Element)=input.hasAttr("disabled") || input.parents().any { it.tagName()=="fieldset" && it.hasAttr("disabled") }
    val radios = form.select("#table1 input[type=radio][name]").toList().filter { !disabled(it) }
    val groups = radios.groupBy { it.attr("name") }
    check(groups.size in 1..200) { "评价表解析错误：未找到有效的评级题目" }
    val signature = StringBuilder(key(action))
    val choices = mutableListOf<String>()
    for ((index, entry) in groups.entries.withIndex()) {
      val (name, options) = entry
      val selected = if (index == groups.size - 1) settings.lastOption else settings.option
      check(selected < options.size) { "第 ${index + 1} 题只有 ${options.size} 个选项，无法选择第 ${selected + 1} 项" }
      val row = options.first().closest("tr") ?: error("评价表解析错误：题目行缺失")
      val question = row.select("td,th").firstOrNull { it.select("input[type=radio]").isEmpty() && it.text().isNotBlank() && !it.text().trim().matches(Regex("\\d+")) }?.text()?.trim().orEmpty().take(200).ifBlank { "第${index + 1}题" }
      val labels = options.mapIndexed { position, input ->
        val id = input.id()
        val label = if (id.isNotBlank()) form.select("label[for]").firstOrNull { it.attr("for") == id } else null
        val adjacent = (input.nextSibling() as? org.jsoup.nodes.TextNode)?.text()?.trim().orEmpty()
        (label?.text()?.trim().orEmpty().ifBlank { input.closest("label")?.text()?.trim().orEmpty() }.ifBlank { adjacent }
          .ifBlank { input.parent()?.takeIf { it.select("input[type=radio]").size==1 }?.ownText()?.trim().orEmpty() })
          .take(160).ifBlank { "第 ${position + 1} 项" }
      }
      signature.append('|').append(name).append(':').append(options.joinToString(",") { it.attr("value") }).append(':').append(labels.joinToString(","))
      options.forEachIndexed { position, input -> if (position == selected) input.attr("checked", "checked") else input.removeAttr("checked") }
      choices.add("$question：${labels[selected]}（第 ${selected + 1} 项）")
    }
    val fields = mutableListOf<Pair<String, String>>()
    form.select("input[name],textarea[name],select[name]").forEach { input ->
      if (disabled(input)) return@forEach
      val name = input.attr("name")
      val type = input.attr("type").lowercase()
      if (type in listOf("submit", "button", "reset", "image", "file") || type in listOf("radio", "checkbox") && !input.hasAttr("checked")) return@forEach
      val values = when (input.tagName()) {
        "textarea" -> listOf(settings.comment.ifBlank { input.text() })
        "select" -> input.select("option[selected]").toList().ifEmpty { if(input.hasAttr("multiple"))emptyList() else input.select("option").take(1) }
          .filter { !it.hasAttr("disabled") && it.parent()?.let { p->p.tagName()=="optgroup" && p.hasAttr("disabled") }!=true }.map { if(it.hasAttr("value"))it.attr("value") else it.text() }
        else -> listOf(if (name == "issubmit") "1" else if(type in listOf("radio","checkbox") && !input.hasAttr("value"))"on" else input.attr("value"))
      }
      check(!input.hasAttr("required") || values.any { it.isNotBlank() }) { "评价表仍有必填项未填写（$name），请补充评语或在学校网页处理" }
      values.forEach { fields.add(name to it) }
      if(type=="hidden" && Regex("id|bh|dm|xh|course|teacher|batch|student|semester|xnxq",RegexOption.IGNORE_CASE).containsMatchIn(name) &&
        !Regex("csrf|token|nonce|session",RegexOption.IGNORE_CASE).containsMatchIn(name))signature.append("|identity:").append(name).append('=').append(values.joinToString(","))
      if(input.tagName()=="textarea") {
        signature.append("|comment:").append(name)
        if(values.any { it.isNotBlank() })choices.add("评语（$name）：${values.joinToString(" ")}")
      }
    }
    check(groups.keys.all { name -> fields.count { it.first == name } == 1 }) { "评价表解析错误：评级选项数量异常" }
    val charset = form.attr("accept-charset").trim().split(Regex("[ ,]+")).firstOrNull { it.isNotBlank() } ?: document.charset().name()
    check(runCatching { java.nio.charset.Charset.isSupported(charset) }.getOrDefault(false)) { "评价表解析错误：网站表单编码不支持" }
    return Form(action, fields, hash(signature.toString()), choices, charset)
  }
}
