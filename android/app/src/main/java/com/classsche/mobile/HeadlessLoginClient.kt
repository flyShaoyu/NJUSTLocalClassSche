package com.classsche.mobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

internal class HeadlessLoginClient(
  private val logger: (scope: String, status: String, message: String) -> Unit,
  private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
) {
  private val cookieJar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
  data class LoginResult(
    val cookies: Map<String, String>,
    val timetableHtml: String
  )

  fun login(
    loginUrl: String,
    timetableUrl: String,
    username: String,
    password: String,
    desiredSemester: String = ""
  ): LoginResult {
    // Reuse WebView cookies within their original domain/path boundaries.
    for (origin in listOf(timetableUrl, "https://ids.njust.edu.cn/authserver/")) {
      android.webkit.CookieManager.getInstance().getCookie(origin).orEmpty().split(';').forEach { raw ->
        val parts = raw.trim().split('=', limit = 2)
        if (parts.size == 2) {
          val cookie = HttpCookie(parts[0], parts[1]).apply {
            path = if (origin.contains("ids.njust.edu.cn")) "/authserver" else "/njlgdx"
            secure = true
            version = 0
          }
          cookieJar.cookieStore.add(URI(origin), cookie)
        }
      }
    }
    val existing = fetchDocument(timetableUrl, null)
    if (looksLikeTimetableDocument(existing)) return timetableResult(existing, timetableUrl, desiredSemester)
    val loginDocument = fetchDocument(loginUrl, referer = null)
    if (loginDocument.selectFirst("#pwdEncryptSalt") != null) {
      return loginIdentity(loginDocument, timetableUrl, username, password, desiredSemester)
    }
    // A still-valid identity session can complete the SSO redirect without a form.
    if (!looksLikeLoginDocument(loginDocument)) {
      val timetable = fetchDocument(timetableUrl, loginUrl)
      if (looksLikeTimetableDocument(timetable)) return timetableResult(timetable, timetableUrl, desiredSemester)
    }
    logger("HEADLESS_LOGIN", "WARN", "教务入口返回旧版登录页，改走统一认证")
    val identityDocument = fetchDocument(UniversityEndpoints.PORTAL_LOGIN, referer = loginUrl)
    if (identityDocument.selectFirst("#pwdEncryptSalt") != null) {
      return loginIdentity(identityDocument, timetableUrl, username, password, desiredSemester)
    }
    if (!looksLikeLoginDocument(identityDocument)) {
      val timetable = fetchDocument(timetableUrl, identityDocument.location())
      if (looksLikeTimetableDocument(timetable)) return timetableResult(timetable, timetableUrl, desiredSemester)
    }
    throw IllegalStateException("统一认证未能恢复教务会话，请检查认证状态后再试")
  }

  private fun loginIdentity(
    document: Document, timetableUrl: String, username: String, password: String, desiredSemester: String,
  ): LoginResult {
    if (username.isBlank() || password.isBlank()) {
      throw IllegalStateException("未保存账号和密码，现有登录状态也已失效；请先在登录页完成一次认证")
    }
    val loginUrl = document.location()
    val form = document.selectFirst("form#pwdFromId") ?: throw IllegalStateException("未找到统一认证表单")
    val salt = form.selectFirst("#pwdEncryptSalt")?.attr("value").orEmpty()
    val parameters = extractFormParameters(form).apply {
      remove("passwordText")
      this["username"] = username
      this["password"] = encryptIdentityPassword(password, salt)
    }
    val result = executeRequest(resolveActionUrl(loginUrl, form), "POST", loginUrl, encodeFormBody(parameters))
    if (looksLikeLoginDocument(parseHtml(result.body, result.url))) {
      throw IllegalStateException("统一认证未完成，请检查统一认证密码或在认证网页完成验证")
    }
    val timetable = fetchDocument(timetableUrl, loginUrl)
    if (!looksLikeTimetableDocument(timetable)) throw IllegalStateException("统一认证后未取得教务会话，请重新登录")
    return timetableResult(timetable, timetableUrl, desiredSemester)
  }

  private fun timetableResult(document: Document, timetableUrl: String, desiredSemester: String): LoginResult {
    val selectedDocument = selectTimetableSemester(document, desiredSemester.trim())
    return LoginResult(sessionCookies(timetableUrl), selectedDocument.outerHtml())
  }

  private fun selectTimetableSemester(document: Document, desiredSemester: String): Document {
    if (desiredSemester.isBlank()) return document
    val select = document.selectFirst("select[name=xnxq01id]") ?: document.selectFirst("#xnxq01id")
      ?: throw IllegalStateException("课表页面缺少学期选项，无法静默更新所选学期")
    val options = select.select("option")
    if (options.none { it.attr("value").trim().ifBlank { it.text().trim() } == desiredSemester }) {
      throw IllegalStateException("网站课表中没有所选学期 $desiredSemester，原缓存已保留")
    }
    val currentSemester = select.selectFirst("option[selected]")?.attr("value")?.trim()
      .orEmpty().ifBlank { options.firstOrNull()?.attr("value")?.trim().orEmpty() }
    if (currentSemester == desiredSemester) return document

    val form = select.parents().firstOrNull { it.tagName().equals("form", ignoreCase = true) }
      ?: throw IllegalStateException("课表页面缺少学期查询表单，原缓存已保留")
    val parameters = extractFormParameters(form).apply {
      this["xnxq01id"] = desiredSemester
      if (containsKey("zc")) this["zc"] = ""
    }
    val method = form.attr("method").ifBlank { "POST" }.uppercase()
    val actionUrl = resolveActionUrl(document.location(), form)
    val requestUrl = if (method == "GET") {
      actionUrl + (if (actionUrl.contains('?')) "&" else "?") + encodeFormBody(parameters)
    } else {
      actionUrl
    }
    logger("HEADLESS_LOGIN", "INFO", "静默切换课表学期 $currentSemester → $desiredSemester")
    val response = executeRequest(requestUrl, method, document.location(),
      if (method == "POST") encodeFormBody(parameters) else null)
    val selectedDocument = parseHtml(response.body, response.url)
    if (!looksLikeTimetableDocument(selectedDocument)) {
      throw IllegalStateException("切换学期后未获取到有效课表，原缓存已保留")
    }
    val selectedSelect = selectedDocument.selectFirst("select[name=xnxq01id]")
      ?: throw IllegalStateException("切换学期后页面缺少学期选项，原缓存已保留")
    val selectedSemester = selectedSelect.selectFirst("option[selected]")?.attr("value")?.trim()
      .orEmpty().ifBlank { selectedSelect.selectFirst("option")?.attr("value")?.trim().orEmpty() }
    if (selectedSemester != desiredSemester) {
      throw IllegalStateException("网站返回学期 $selectedSemester，与所选学期 $desiredSemester 不一致；原缓存已保留")
    }
    return selectedDocument
  }

  private fun encryptIdentityPassword(password: String, salt: String): String {
    require(salt.toByteArray(Charsets.UTF_8).size in listOf(16, 24, 32)) { "统一认证加密参数无效" }
    val random = SecureRandom()
    val alphabet = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    fun randomText(size: Int) = (1..size).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
      IvParameterSpec(randomText(16).toByteArray(Charsets.UTF_8)))
    return Base64.getEncoder().encodeToString(cipher.doFinal((randomText(64) + password).toByteArray(Charsets.UTF_8)))
  }

  private fun sessionCookies(url: String): Map<String, String> = cookieJar.get(URI(url), emptyMap())["Cookie"].orEmpty()
    .flatMap { it.split(';') }.mapNotNull { raw ->
      val pair = raw.trim().split('=', limit = 2)
      if (pair.size == 2 && !pair[0].startsWith('$')) pair[0] to pair[1] else null
    }.toMap()

  private fun fetchDocument(
    url: String,
    referer: String?,
  ): Document {
    val response = executeRequest(url = url, method = "GET", referer = referer)
    return parseHtml(response.body, response.url)
  }

  private fun resolveActionUrl(loginUrl: String, form: Element): String {
    val action = form.absUrl("action").ifBlank { form.attr("action") }
    if (action.isBlank()) return loginUrl
    val base = URL(loginUrl)
    val resolved = URL(base, action)
    // The IDS page adds ?service via JavaScript; the raw HTML action omits it.
    if (base.host == "ids.njust.edu.cn" && resolved.host == base.host &&
      resolved.query.isNullOrBlank() && !base.query.isNullOrBlank()) {
      return "$resolved?${base.query}"
    }
    return resolved.toString()
  }

  private fun extractFormParameters(form: Element): LinkedHashMap<String, String> {
    val result = linkedMapOf<String, String>()
    form.select("input[name], textarea[name], select[name]").forEach { field ->
      val name = field.attr("name").trim()
      if (name.isBlank()) return@forEach
      when (field.tagName().lowercase()) {
        "select" -> {
          result[name] = field.selectFirst("option[selected]")?.attr("value")
            ?: field.selectFirst("option")?.attr("value")
            ?: ""
        }
        "textarea" -> {
          result[name] = field.text()
        }
        else -> {
          when (field.attr("type").lowercase()) {
            "submit", "button", "file", "image", "reset" -> Unit
            "checkbox", "radio" -> if (field.hasAttr("checked")) {
              result[name] = field.attr("value").ifBlank { "on" }
            }
            else -> result[name] = field.attr("value")
          }
        }
      }
    }
    return result
  }

  private fun executeRequest(
    url: String,
    method: String,
    referer: String?,
    formBody: String? = null
  ): HttpResponse {
    var currentUrl = url
    var currentMethod = method.uppercase()
    var currentReferer = referer
    var currentBody = formBody

    repeat(8) {
      val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = currentMethod
        useCaches = false
        instanceFollowRedirects = false
        connectTimeout = 10000
        readTimeout = 10000
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        setRequestProperty("User-Agent", userAgent)
        currentReferer?.let { setRequestProperty("Referer", it) }
        cookieJar.get(URI(currentUrl), emptyMap())["Cookie"]?.joinToString("; ")
          ?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
      }

      if (currentMethod == "POST" && currentBody != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { output ->
          output.write(currentBody!!.toByteArray(Charsets.UTF_8))
        }
      }

      val code = connection.responseCode
      cookieJar.put(URI(currentUrl), connection.headerFields.filterKeys { it != null })
      val body = (if (code >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream)
        ?.use { it.readBytes() }
        ?: ByteArray(0)
      val location = connection.getHeaderField("Location")
      connection.disconnect()

      if (code in 300..399 && !location.isNullOrBlank()) {
        currentReferer = currentUrl
        currentUrl = URL(URL(currentUrl), location).toString()
        if (currentUrl.startsWith("http://bkjw.njust.edu.cn/")) currentUrl = currentUrl.replaceFirst("http:", "https:")
        if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
          currentMethod = "GET"
          currentBody = null
        }
        logger("HEADLESS_HTTP", "INFO", "重定向到 ${URL(currentUrl).host}${URL(currentUrl).path} code=$code")
        return@repeat
      }

      if (code in 300..399) {
        throw IllegalStateException("登录步骤目标网站跳转错误：HTTP $code，${URL(currentUrl).host}${URL(currentUrl).path} 未提供 Location")
      }
      if (code !in 200..299) {
        throw IllegalStateException("登录步骤网站返回错误：HTTP $code，地址 ${URL(currentUrl).host}${URL(currentUrl).path}")
      }

      logger("HEADLESS_HTTP", "INFO", "$currentMethod ${URL(currentUrl).host}${URL(currentUrl).path} code=$code")
      return HttpResponse(code = code, body = body, url = currentUrl)
    }

    throw IllegalStateException("HTTP 重定向次数过多")
  }

  private fun parseHtml(bytes: ByteArray, baseUrl: String): Document =
    Jsoup.parse(ByteArrayInputStream(bytes), null, baseUrl)

  private fun looksLikeLoginDocument(document: Document): Boolean {
    val title = document.title().lowercase()
    if (title.contains("login") || title.contains("登录")) return true
    if (document.selectFirst("input[type=password]") != null) return true
    if (document.selectFirst("#SafeCodeImg") != null) return true
    return false
  }

  private fun looksLikeTimetableDocument(document: Document): Boolean {
    return !looksLikeLoginDocument(document) && document.selectFirst("#kbtable") != null
  }

  private fun encodeFormBody(parameters: Map<String, String>): String =
    parameters.entries.joinToString("&") { (key, value) ->
      "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

  private data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val url: String
  )

}
