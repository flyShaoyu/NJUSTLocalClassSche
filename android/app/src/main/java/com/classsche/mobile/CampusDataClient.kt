package com.classsche.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal class CampusLoginRequired(message: String) : IllegalStateException(message)

/** School queries only. Password submission, enrollment, renewal and reservations are not exposed. */
internal class CampusDataClient(private val logger: (String, String, String) -> Unit, private val cookies: LoginCookieStore = WebViewLoginCookieStore) {
  companion object {
    const val LIBRARY = "http://202.119.83.14:8080/uopac/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
  }
  private val http = CampusHttp(cookies, logger)
  private val publicHttp = CampusHttp(MemoryCookies(), logger)
  private var lastTeachingCheck = 0L
  private var teacherOptions = emptyList<Pair<String, String>>()

  private fun ensureTeaching() {
    if (lastTeachingCheck != 0L && System.nanoTime() - lastTeachingCheck < 30_000_000_000L) return
    try {
      // Existing login lock, redirect handling and Cookie persistence, with no
      // credentials supplied: an expired CAS session cannot cause a password POST.
      HeadlessLoginClient(logger, UA, cookies).login(UniversityEndpoints.LOGIN, UniversityEndpoints.TIMETABLE, "", "")
      lastTeachingCheck = System.nanoTime()
    } catch (error: Exception) {
      if (error.message.orEmpty().contains("未保存账号和密码")) throw CampusLoginRequired("登录状态已失效，请先完成统一认证后再查询")
      throw error
    }
  }

  private fun teaching(path: String, form: Map<String, String>? = null): Document {
    ensureTeaching()
    val document = http.document(UniversityEndpoints.ORIGIN + path, form)
    if (document.selectFirst("input[type=password]") != null) {
      lastTeachingCheck = 0
      throw CampusLoginRequired("教务登录状态已失效，请重新登录后查询")
    }
    return document
  }

  private fun optionJson(document: Document, name: String): JSONArray = JSONArray().apply {
    document.select("select[name=$name] option").toList().filter { it.attr("value").isNotBlank() }.forEach { put(JSONObject().put("id", it.attr("value")).put("label", it.text().trim())) }
  }

  fun roomFilters(campus: String): JSONObject {
    require(campus in listOf("01", "4y")) { "请选择有效的校区" }
    val form = teaching("/njlgdx/kbxx/jsjy_query")
    check(form.selectFirst("select[name=xnxqh]") != null) { "空闲教室解析错误：查询表单缺失" }
    val text = http.text(UniversityEndpoints.ORIGIN + "/njlgdx/kbcx/getJxlByAjax", mapOf("xqid" to campus)).second
    val values = runCatching { JSONArray(text) }.getOrElse { throw IllegalStateException("教学楼列表解析错误：网站未返回有效列表") }
    val buildings = JSONArray()
    for (i in 0 until values.length()) {
      val value = values.getJSONObject(i)
      check(value.has("dm") && value.has("dmmc")) { "教学楼列表解析错误：缺少教学楼编号或名称" }
      buildings.put(JSONObject().put("id", value.getString("dm")).put("label", value.getString("dmmc")))
    }
    return JSONObject().put("semesters", optionJson(form, "xnxqh")).put("buildings", buildings).put("periods", optionJson(form, "jc"))
  }

  fun rooms(input: JSONObject): JSONObject {
    val semester = input.getString("semester")
    val campus = input.getString("campus")
    val week = input.getInt("week"); val day = input.getInt("weekday")
    require(week in 1..30 && day in 1..7 && campus in listOf("01", "4y")) { "周次、星期或校区无效" }
    val ranges = if (input.has("periodRanges")) {
      val raw = input.getJSONArray("periodRanges")
      require(raw.length() in 1..6) { "请选择1至6个时段" }
      (0 until raw.length()).map { i -> raw.getJSONObject(i).let { it.getInt("startPeriod") to it.getInt("endPeriod") } }
    } else listOf(input.getInt("startPeriod") to input.getInt("endPeriod"))
    require(ranges.all { (start,end) -> start in 1..14 && end in start..14 }) { "节次范围无效" }
    val intervals = ranges.distinct().sortedBy { it.first }

    val document = teaching("/njlgdx/kbxx/jsjy_query")
    val form = document.selectFirst("form[action=/njlgdx/kbxx/jsjy_query2]") ?: error("空闲教室解析错误：查询表单缺失")
    val selected = mapOf("xnxqh" to semester, "xqbh" to campus, "zc" to "$week", "zc2" to "$week", "xq" to "$day", "xq2" to "$day", "jszt" to "5")
    val selections = intervals.map { (start,end) -> selected + mapOf("jc" to start.toString().padStart(2,'0'), "jc2" to end.toString().padStart(2,'0')) }
    selections.forEach { values -> values.forEach { (name, value) -> require(document.select("select[name=$name] option").any { it.attr("value") == value }) { "网站不支持所选的 $name，请重新选择" } } }
    val building = input.optString("building")
    if (building.isNotBlank()) {
      val options = roomFilters(campus).getJSONArray("buildings")
      require((0 until options.length()).any { options.getJSONObject(it).getString("id") == building }) { "教学楼不属于所选校区" }
    }
    val results = selections.map { values ->
      check(!Thread.currentThread().isInterrupted) { "查询已取消" }
      val params = parameters(form).apply {
        putAll(values)
        putAll(mapOf("jxlbh" to building, "jxqbh" to "", "jsbh" to "", "rnrs" to "", "bjfh" to "="))
      }
      CampusDataParser.rooms(teaching("/njlgdx/kbxx/jsjy_query2", params))
    }
    val rooms = CampusDataParser.intersectFreeRooms(results)
    return JSONObject().put("rooms", JSONArray().apply { rooms.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
      .put("summary", "第${week}周 周${listOf("一", "二", "三", "四", "五", "六", "日")[day - 1]} · " + intervals.joinToString("、") { (start,end) -> if(start==end) "$start" else "$start—$end" } + "节均空闲")
  }

  fun searchBooks(keyword: String, page: Int): JSONObject {
    require(keyword.trim().length in 1..120 && page in 1..10000) { "请填写书名关键词及有效页码" }
    val document = publicHttp.document(LIBRARY + "opac/search.php")
    val form = document.selectFirst("form:has(input[name=strText]),form:has(input[name=q])") ?: error("图书搜索解析错误：检索表单缺失")
    check(form.attr("method").equals("get", true) && form.attr("action") == "openlink.php") { "图书搜索解析错误：查询表单目标已变化" }
    val params = parameters(form).apply {
      if (form.selectFirst("input[name=q]") != null) putAll(mapOf("t" to "title", "m" to "f", "h" to "1", "q" to keyword.trim(), "d" to "ALL", "e" to "on", "pn" to "20", "i" to "l", "o" to "CATA_DATE", "da" to "DESC", "b" to "ALL", "page" to "$page"))
      else putAll(mapOf("strSearchType" to "title", "match_flag" to "forward", "historyCount" to "1", "strText" to keyword.trim(), "doctype" to "ALL", "with_ebook" to "on", "displaypg" to "20", "showmode" to "list", "sort" to "CATA_DATE", "orderby" to "desc", "dept" to "ALL", "page" to "$page"))
    }
    val result = publicHttp.document(LIBRARY + "opac/openlink.php?" + encode(params))
    val total = CampusDataParser.bookTotal(result)
    return JSONObject().put("total", total).put("page", page).put("books", JSONArray().apply {
      CampusDataParser.books(result).forEach { b -> put(JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("publisher", b.publisher).put("year", b.year).put("callNumber", b.callNumber).put("copies", b.copies).put("available", b.available)) }
    })
  }

  fun bookDetail(id: String): JSONObject {
    require(id.length in 1..200 && id.matches(Regex("[A-Za-z0-9%+=/_-]+"))) { "书目编号无效" }
    val document = publicHttp.document(LIBRARY + "opac/item.php?marc_no=" + encodeValue(id))
    val fields = JSONArray()
    document.select("dl").toList().filter { it.selectFirst("dt") != null }.forEach { dl ->
      val name = dl.selectFirst("dt")?.text().orEmpty().trim().removeSuffix(":").removeSuffix("：")
      val value = dl.select("dd").joinToString(" ") { it.text().trim() }
      if (name.isNotBlank() && value.isNotBlank() && fields.length() < 18) fields.put(JSONObject().put("label", name).put("value", value))
    }
    val holdings = JSONArray().apply { CampusDataParser.holdings(document).forEach { put(JSONObject().put("callNumber", it.callNumber).put("location", it.location).put("status", it.status)) } }
    return JSONObject().put("fields", fields).put("holdings", holdings)
  }

  /** Read-only and memory-only; do not fetch books/history or persist this response. */
  fun readerStatus(): JSONObject {
    val document=http.document(LIBRARY+"reader/redr_info.php")
    if (!CampusDataParser.readerAuthenticated(document)) throw CampusLoginRequired("请先完成图书馆独立登录")
    val cards=CampusDataParser.readerSummary(document)
    return JSONObject().put("cards",JSONArray().apply { cards.forEach { (label,value)->put(JSONObject().put("label",label).put("value",value)) } })
  }

  fun loans(): JSONObject {
    var document = http.document(LIBRARY + "reader/redr_info.php")
    if (!CampusDataParser.readerAuthenticated(document)) document = http.document(LIBRARY + "reader/login.php")
    if (!CampusDataParser.readerAuthenticated(document)) throw CampusLoginRequired("图书馆尚未登录或会话已失效，请在图书馆登录页完成登录，再返回刷新借阅")
    val loans = CampusDataParser.loans(http.document(LIBRARY + "reader/book_lst.php"))
    return JSONObject().put("loans", JSONArray().apply { loans.forEach { put(JSONObject().put("title", it.title).put("barcode", it.barcode).put("borrowedAt", it.borrowedAt).put("dueAt", it.dueAt).put("location", it.location)) } })
  }

  fun searchCourses(semester: String, keyword: String, teacher: String): JSONObject {
    require(keyword.trim().length in 1..100) { "请输入课程名称关键词" }
    val document = teaching("/njlgdx/kbcx/kbxx_kc")
    val form = document.selectFirst("form:has(select[name=xnxqh])") ?: error("课程搜索解析错误：查询表单缺失")
    require(document.select("select[name=xnxqh] option").any { it.attr("value") == semester }) { "网站不支持所选学期" }
    val params = parameters(form).apply { put("xnxqh", semester); put("kc", keyword.trim()); listOf("skyx", "kkyx", "zzdKcSX", "zc1", "zc2", "jc1", "jc2").forEach { put(it, "") } }
    val list = CampusDataParser.publicCourses(teaching("/njlgdx/kbcx/kbxx_kc_ifr", params)).filter { teacher.isBlank() || it.teacher.contains(teacher.trim(), true) }
    return JSONObject().put("total", list.size).put("courses", JSONArray(TimetableRenderer.toJson(list.take(150))))
  }

  private fun loadTeacherOptions(): Document {
    val document = teaching("/njlgdx/kbcx/kbxx_teacher")
    check(document.selectFirst("select[name=jg0101id]") != null) { "教师列表解析错误：未找到教师下拉选项" }
    teacherOptions = document.select("select[name=jg0101id] option").toList().filter { it.attr("value").isNotBlank() }.map { it.attr("value") to it.text().trim() }
    return document
  }

  fun teachers(keyword: String): JSONObject {
    require(keyword.trim().isNotEmpty() && keyword.length <= 100) { "请输入教师姓名" }
    if (teacherOptions.isEmpty()) loadTeacherOptions()
    val matches = teacherOptions.filter { it.second.contains(keyword.trim(), true) }
    return JSONObject().put("total", matches.size).put("teachers", JSONArray().apply { matches.take(80).forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) } })
  }

  fun teacherTimetable(semester: String, id: String): List<TimetableCourse> {
    val document = loadTeacherOptions()
    require(teacherOptions.any { it.first == id }) { "未找到该教师，请重新搜索" }
    require(document.select("select[name=xnxqh] option").any { it.attr("value") == semester }) { "网站不支持所选学期" }
    val form = document.selectFirst("form:has(select[name=xnxqh])") ?: error("教师课表解析错误：查询表单缺失")
    val params = parameters(form).apply { put("xnxqh", semester); put("jg0101id", id); listOf("skyx", "jszc", "zc1", "zc2", "jc1", "jc2").forEach { put(it, "") } }
    return CampusDataParser.teacherCourses(teaching("/njlgdx/kbcx/kbxx_teacher_ifr", params))
  }

  private fun parameters(form: Element): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
    form.select("input[name],select[name]").forEach { element ->
      val type = element.attr("type").lowercase()
      if (type !in listOf("button", "submit", "password") && (type !in listOf("checkbox", "radio") || element.hasAttr("checked"))) {
        put(element.attr("name"), if (element.tagName() == "select") element.selectFirst("option[selected]")?.attr("value") ?: element.selectFirst("option")?.attr("value").orEmpty() else element.attr("value"))
      }
    }
  }

  private fun encodeValue(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun encode(values: Map<String, String>) = values.entries.joinToString("&") { encodeValue(it.key) + "=" + encodeValue(it.value) }

  private class MemoryCookies : LoginCookieStore {
    private val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    override fun read(url: String): String = jar.get(URI(url), emptyMap())["Cookie"].orEmpty().joinToString("; ")
    override fun receive(url: String, headers: Map<String, List<String>>) = jar.put(URI(url), LoginCookiePolicy.filterHeaders(url, headers))
    override fun flush() = Unit
  }

  private class CampusHttp(private val store: LoginCookieStore, private val logger: (String, String, String) -> Unit) {
    fun document(url: String, form: Map<String, String>? = null): Document {
      val (actual, body) = bytes(url, form)
      return Jsoup.parse(ByteArrayInputStream(body), null, actual)
    }
    fun text(url: String, form: Map<String, String>? = null): Pair<String, String> {
      val (actual, body) = bytes(url, form)
      val utf8 = body.toString(Charsets.UTF_8)
      return actual to if (utf8.contains('\uFFFD')) body.toString(charset("GB18030")) else utf8
    }
    private fun safe(url: String): String = URL(url).let { it.host + it.path }
    private fun bytes(initial: String, form: Map<String, String>?): Pair<String, ByteArray> {
      var url = initial
      var values = form
      repeat(12) {
        val target = URL(url)
        require(target.userInfo == null && when (target.host) {
          "202.119.83.14" -> target.protocol == "http" && target.port == 8080 && target.path.startsWith("/uopac/")
          "ids.njust.edu.cn" -> target.protocol in listOf("http", "https") && target.port in listOf(-1, 80, 443) && target.path.startsWith("/authserver/")
          "bkjw.njust.edu.cn", "ehall2.njust.edu.cn" -> target.protocol == "https" && target.port in listOf(-1, 443)
          else -> false
        }) { "目标网站跳转错误：非预期地址 ${safe(url)}" }
        require(values == null || target.host == "bkjw.njust.edu.cn") { "不允许向认证页提交密码或表单" }
        val connection = target.openConnection() as HttpURLConnection
        try {
          connection.instanceFollowRedirects = false
          if(target.path.startsWith("/uopac/reader/")) connection.useCaches=false
          connection.connectTimeout = 15000; connection.readTimeout = 25000
          connection.requestMethod = if (values == null) "GET" else "POST"
          connection.setRequestProperty("User-Agent", UA)
          connection.setRequestProperty("Accept", "text/html,application/json,*/*")
          store.read(url).takeIf { it.isNotBlank() }?.let { cookie -> connection.setRequestProperty("Cookie", cookie) }
          if (values != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = values!!.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
            connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
          }
          val status = connection.responseCode
          val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
          store.receive(url, headers)
          logger("CAMPUS_HTTP", "INFO", "${connection.requestMethod} ${safe(url)} HTTP $status")
          if (status in listOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location") ?: error("目标网站跳转错误：HTTP $status 未提供 Location")
            val next = URL(target, location)
            if (next.host == "bkjw.njust.edu.cn" && next.protocol == "http") url = URL("https", next.host, -1, next.file).toString() else url = next.toString()
            if (status in listOf(301, 302, 303)) values = null
          } else {
            check(status in 200..299) { "网站返回错误：HTTP $status，${safe(url)}" }
            val body = connection.inputStream.use { input ->
              val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
              while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 8 * 1024 * 1024) { "页面内容过大，请缩小查询范围" }; out.write(buffer, 0, count) }
              out.toByteArray()
            }
            store.flush()
            return url to body
          }
        } catch (error: Exception) {
          val detail = error.message.orEmpty().replace(Regex("https?://[^\\s）)。]+")) { match -> runCatching { safe(match.value) }.getOrDefault("目标页面") }
          throw IllegalStateException("${FailureDetails.describe(IllegalStateException(detail, error))}（${safe(url)}）", error)
        } finally { connection.disconnect() }
      }
      error("目标网站跳转错误：重定向次数过多")
    }
  }
}
