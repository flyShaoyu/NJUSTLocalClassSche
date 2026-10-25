package com.classsche.mobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
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
  private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36",
  private val sharedCookies: LoginCookieStore = WebViewLoginCookieStore
) {
  companion object {
    // Timetable refresh and score sync have different executors. Share one auth attempt.
    private val loginLock = Any()
  }
  private val cookieJar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
  data class LoginResult(
    val cookies: Map<String, String>,
    val timetableHtml: String
  )

  class SessionCookie(val url: String, val header: String)

  class AuthenticationRejectedException(message: String) : IllegalStateException(message)

  class CaptchaRequiredException(
    val loginUrl: String,
    val sessionCookies: List<SessionCookie>
  ) : IllegalStateException("学校要求图形验证码，请在登录页手动填写")

  fun login(
    loginUrl: String,
    timetableUrl: String,
    username: String,
    password: String,
    desiredSemester: String = ""
  ): LoginResult = synchronized(loginLock) {
    try {
      loginWithSession(loginUrl, timetableUrl, username, password, desiredSemester)
    } finally {
      sharedCookies.flush()
    }
  }

  private fun loginWithSession(
    loginUrl: String, timetableUrl: String, username: String, password: String, desiredSemester: String
  ): LoginResult {
    cookieJar.cookieStore.removeAll()
    logger("HEADLESS_SESSION", "INFO", "优先复用已保存的教务 Cookie")
    // Reuse WebView cookies within their original domain/path boundaries.
    for (origin in listOf(timetableUrl, "https://ids.njust.edu.cn/authserver/")) {
      sharedCookies.read(origin).split(';').forEach { raw ->
        val parts = raw.trim().split('=', limit = 2)
        if (parts.size == 2) {
          val cookie = HttpCookie(parts[0], parts[1]).apply {
            domain = URI(origin).host
            path = if (origin.contains("ids.njust.edu.cn")) "/authserver" else "/njlgdx"
            secure = true
            version = 0
          }
          cookieJar.cookieStore.add(URI(origin), cookie)
        }
      }
    }
    val existing = fetchDocument(timetableUrl, null, allowSessionExpired = true)
    if (looksLikeTimetableDocument(existing)) {
      logger("HEADLESS_SESSION", "SUCCESS", "教务 Cookie 有效，跳过统一认证和密码提交")
      return timetableResult(existing, timetableUrl, desiredSemester)
    }
    if (!looksLikeLoginDocument(existing) && existing.selectFirst("[data-classsche-session-expired]") == null) {
      throw IllegalStateException("页面解析错误：教务会话检查未返回课表或登录页，保留原缓存且不提交密码")
    }
    logger("HEADLESS_SESSION", "INFO", "教务会话已失效，尝试复用统一认证会话恢复 SSO")
    val loginDocument = fetchDocument(loginUrl, referer = null)
    if (loginDocument.selectFirst("#pwdEncryptSalt") != null) {
      return loginIdentity(loginDocument, timetableUrl, username, password, desiredSemester)
    }
    // A still-valid identity session can complete the SSO redirect without a form.
    if (!looksLikeLoginDocument(loginDocument)) {
      val timetable = fetchDocument(timetableUrl, loginUrl)
      if (looksLikeTimetableDocument(timetable)) {
        logger("HEADLESS_SESSION", "SUCCESS", "统一认证 Cookie 有效，已恢复教务会话，未提交密码")
        return timetableResult(timetable, timetableUrl, desiredSemester)
      }
      if (!looksLikeLoginDocument(timetable)) throw IllegalStateException("页面解析错误：SSO 后未返回有效课表，不提交密码")
    }
    logger("HEADLESS_LOGIN", "WARN", "教务入口返回旧版登录页，改走统一认证")
    val identityDocument = fetchDocument(UniversityEndpoints.PORTAL_LOGIN, referer = loginUrl)
    if (identityDocument.selectFirst("#pwdEncryptSalt") != null) {
      return loginIdentity(identityDocument, timetableUrl, username, password, desiredSemester)
    }
    if (!looksLikeLoginDocument(identityDocument)) {
      val timetable = fetchDocument(timetableUrl, identityDocument.location())
      if (looksLikeTimetableDocument(timetable)) {
        logger("HEADLESS_SESSION", "SUCCESS", "统一认证 Cookie 有效，已恢复教务会话，未提交密码")
        return timetableResult(timetable, timetableUrl, desiredSemester)
      }
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
    // Check before submitting: repeated attempts would only invalidate the image challenge.
    val captchaUrl = URL(URL(loginUrl), "/authserver/checkNeedCaptcha.htl?username=${URLEncoder.encode(username, "UTF-8")}").toString()
    val captchaResponse = executeRequest(captchaUrl, "GET", loginUrl)
    val captchaRequirement = runCatching { JSONObject(captchaResponse.body.toString(Charsets.UTF_8)) }.getOrNull()
      ?: throw IllegalStateException("学校验证码要求解析错误：接口返回格式不正确")
    val required = captchaRequirement.opt("isNeed") as? Boolean
      ?: throw IllegalStateException("学校验证码要求解析错误：接口缺少 isNeed 字段")
    val alwaysRequired = Regex("""\b(?:var\s+)?badCredentialsCount\s*=\s*["']?0["']?\s*;""")
      .containsMatchIn(document.select("script:not([src])").joinToString("\n") { it.data() })
    if (required || alwaysRequired) throw captchaRequired(loginUrl)
    logger("HEADLESS_LOGIN", "INFO", "现有会话已失效，验证码检查通过，本次提交一次账号密码")
    val salt = form.selectFirst("#pwdEncryptSalt")?.attr("value").orEmpty()
    val parameters = extractFormParameters(form).apply {
      remove("passwordText")
      this["username"] = username
      this["password"] = encryptIdentityPassword(password, salt)
    }
    val result = executeRequest(resolveActionUrl(loginUrl, form), "POST", loginUrl, encodeFormBody(parameters), allowIdentityFailure = true)
    val resultDocument = parseHtml(result.body, result.url)
    if (looksLikeLoginDocument(resultDocument)) {
      val siteMessage = resultDocument.select("#showErrorTip, #showWarnTip, #formErrorTip")
        .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString("；")
      if (siteMessage.contains("图形动态码") || siteMessage.contains("图形验证码")) {
        throw captchaRequired(result.url)
      }
      val detail = siteMessage.ifBlank { "请检查统一认证密码或在认证网页完成验证" }
      throw AuthenticationRejectedException("统一认证未完成：$detail" + if (result.code >= 400) "（HTTP ${result.code}）" else "")
    }
    if (result.code == 401) throw AuthenticationRejectedException("统一认证被拒绝：HTTP 401，网站未提供具体原因，请手动检查登录状态")
    if (result.code !in 200..299) throw IllegalStateException("统一认证网站返回错误：HTTP ${result.code}")
    val timetable = fetchDocument(timetableUrl, loginUrl)
    if (!looksLikeTimetableDocument(timetable)) throw IllegalStateException("统一认证后未取得教务会话，请重新登录")
    return timetableResult(timetable, timetableUrl, desiredSemester)
  }

  private fun captchaRequired(loginUrl: String): CaptchaRequiredException {
    // Preserve domain/path scope while handing the HTTP session to WebView.
    // CookieStore normalizes its URI list to HTTP, which would hide Secure cookies
    // when querying by those URIs. Read the full store and retain the original scope.
    val cookies = cookieJar.cookieStore.cookies.filter { !it.hasExpired() }.mapNotNull { cookie ->
      val host = cookie.domain?.trimStart('.')?.lowercase()
      if (host !in setOf("ids.njust.edu.cn", "ehall2.njust.edu.cn", "bkjw.njust.edu.cn", "njust.edu.cn")) return@mapNotNull null
      val path = cookie.path?.takeIf { it.startsWith('/') } ?: "/"
      val targetHost = if (host == "njust.edu.cn") "ids.njust.edu.cn" else host
      SessionCookie("https://$targetHost$path", buildString {
        append("${cookie.name}=${cookie.value}; Path=$path")
        cookie.domain?.takeIf { it.isNotBlank() }?.let { append("; Domain=$it") }
        if (cookie.secure) append("; Secure")
        if (cookie.isHttpOnly) append("; HttpOnly")
        if (cookie.maxAge >= 0) append("; Max-Age=${cookie.maxAge}")
      })
    }.distinctBy { it.url to it.header }
    return CaptchaRequiredException(loginUrl, cookies)
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
    allowSessionExpired: Boolean = false,
  ): Document {
    val response = executeRequest(url = url, method = "GET", referer = referer, allowSessionExpired = allowSessionExpired)
    if (response.code == 401 && allowSessionExpired) {
      return Jsoup.parse("<div data-classsche-session-expired></div>", response.url)
    }
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
    formBody: String? = null,
    allowIdentityFailure: Boolean = false,
    allowSessionExpired: Boolean = false
  ): HttpResponse {
    var currentUrl = url
    var currentMethod = method.uppercase()
    var currentReferer = referer
    var currentBody = formBody

    repeat(8) {
      val target = URL(currentUrl)
      if (target.protocol != "https" || target.host !in setOf("ids.njust.edu.cn", "ehall2.njust.edu.cn", "bkjw.njust.edu.cn")) {
        throw IllegalStateException("目标网站跳转错误：登录请求进入非预期站点 ${target.host}${target.path}")
      }
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
      val responseHeaders = connection.headerFields.filterKeys { it != null }
      cookieJar.put(URI(currentUrl), responseHeaders)
      val body = (if (code >= 400) connection.errorStream else connection.inputStream)
        ?.use { it.readBytes() }
        ?: ByteArray(0)
      val location = connection.getHeaderField("Location")
      connection.disconnect()
      // Keep each original Set-Cookie, rather than copying a flat name/value map
      // across hosts. This also propagates expired cookies and rotating sessions.
      sharedCookies.receive(currentUrl, responseHeaders)

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
      val identityFailure = allowIdentityFailure && code == 401 &&
        URL(currentUrl).host == "ids.njust.edu.cn" && URL(currentUrl).path == "/authserver/login"
      val expiredSession = allowSessionExpired && code == 401 && URL(currentUrl).host == "bkjw.njust.edu.cn"
      if (code !in 200..299 && !identityFailure && !expiredSession) {
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
