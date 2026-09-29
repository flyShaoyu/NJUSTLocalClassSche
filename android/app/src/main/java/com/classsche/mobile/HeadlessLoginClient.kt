package com.classsche.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
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
    val timetableHtml: String,
    val captchaAttempts: Int
  )

  fun login(
    loginUrl: String,
    timetableUrl: String,
    username: String,
    password: String,
    recognizeCaptcha: (Bitmap) -> String?
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
    if (looksLikeTimetableDocument(existing)) return LoginResult(sessionCookies(timetableUrl), existing.outerHtml(), 0)
    var loginDocument = fetchDocument(loginUrl, referer = null)
    if (loginDocument.selectFirst("#pwdEncryptSalt") != null) {
      return loginIdentity(loginDocument, timetableUrl, username, password)
    }
    // A still-valid identity session can complete the SSO redirect without a form.
    if (!looksLikeLoginDocument(loginDocument)) {
      val timetable = fetchDocument(timetableUrl, loginUrl)
      if (looksLikeTimetableDocument(timetable)) return LoginResult(sessionCookies(timetableUrl), timetable.outerHtml(), 0)
    }
    logger("HEADLESS_LOGIN", "INFO", "已拉取登录页 title=${loginDocument.title().ifBlank { "-" }}")

    repeat(6) { attemptIndex ->
      val form = loginDocument.selectFirst("form") ?: throw IllegalStateException("未找到登录表单")
      val captchaUrl = resolveCaptchaUrl(loginDocument, loginUrl)
      val captchaBitmap = fetchBitmap(captchaUrl, referer = loginUrl)
      val captchaText = recognizeCaptcha(captchaBitmap)
        ?.replace(Regex("[^a-zA-Z0-9]"), "")
        ?.take(4)
        .orEmpty()

      if (captchaText.length != 4) {
        logger("HEADLESS_LOGIN", "WARN", "第 ${attemptIndex + 1} 次验证码识别失败：$captchaText")
        loginDocument = fetchDocument(loginUrl, referer = loginUrl)
        return@repeat
      }

      val method = form.attr("method").ifBlank { "post" }.uppercase()
      val actionUrl = resolveActionUrl(loginUrl, form)
      val formData = extractFormParameters(form).apply {
        this[findFieldName(form, USERNAME_SELECTORS) ?: "username"] = username
        this[findFieldName(form, PASSWORD_SELECTORS) ?: "password"] = password
        this[findFieldName(form, CAPTCHA_SELECTORS) ?: "RANDOMCODE"] = captchaText
      }

      logger(
        "HEADLESS_LOGIN",
        "INFO",
        "第 ${attemptIndex + 1} 次提交 action=$actionUrl method=$method captcha=$captchaText"
      )
      val submitResponse = executeRequest(
        url = actionUrl,
        method = method,
        referer = loginUrl,
        formBody = if (method == "POST") encodeFormBody(formData) else null
      )
      val submitDocument = parseHtml(submitResponse.body, actionUrl)
      logger(
        "HEADLESS_LOGIN",
        if (looksLikeLoginDocument(submitDocument)) "WARN" else "INFO",
        "提交后响应码=${submitResponse.code} title=${submitDocument.title().ifBlank { "-" }}"
      )

      val timetableResponse = executeRequest(
        url = timetableUrl,
        method = "GET",
        referer = loginUrl
      )
      val timetableDocument = parseHtml(timetableResponse.body, timetableUrl)
      if (looksLikeTimetableDocument(timetableDocument)) {
        logger("HEADLESS_LOGIN", "SUCCESS", "纯 HTTP 登录成功，验证码尝试次数=${attemptIndex + 1}")
        return LoginResult(
          cookies = sessionCookies(timetableUrl),
          timetableHtml = timetableDocument.outerHtml(),
          captchaAttempts = attemptIndex + 1
        )
      }

      logger(
        "HEADLESS_LOGIN",
        "WARN",
        "第 ${attemptIndex + 1} 次登录后仍未进入课表页 title=${timetableDocument.title().ifBlank { "-" }}"
      )
      loginDocument = fetchDocument(loginUrl, referer = timetableUrl)
    }

    throw IllegalStateException("纯 HTTP 登录连续多次失败")
  }

  private fun loginIdentity(
    document: Document, timetableUrl: String, username: String, password: String,
  ): LoginResult {
    val loginUrl = document.location()
    val form = document.selectFirst("form#pwdFromId") ?: throw IllegalStateException("未找到统一认证表单")
    val checkUrl = "https://ids.njust.edu.cn/authserver/checkNeedCaptcha.htl?username=${URLEncoder.encode(username, "UTF-8")}"
    val check = executeRequest(checkUrl, "GET", loginUrl)
    if (org.json.JSONObject(String(check.body, Charsets.UTF_8)).optBoolean("isNeed", true)) {
      throw IllegalStateException("统一认证需要验证码，请在登录页打开认证网页完成验证")
    }
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
    return LoginResult(sessionCookies(timetableUrl), timetable.outerHtml(), 0)
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

  private fun fetchBitmap(
    url: String,
    referer: String?,
  ): Bitmap {
    val response = executeRequest(url = url, method = "GET", referer = referer)
    return ByteArrayInputStream(response.body).use { input ->
      BufferedInputStream(input).use { buffered ->
        BitmapFactory.decodeStream(buffered)
      }
    } ?: throw IllegalStateException("验证码图片解码失败")
  }

  private fun resolveCaptchaUrl(document: Document, loginUrl: String): String {
    val image = document.selectFirst("#SafeCodeImg")
      ?: document.selectFirst("img[src*=SafeCode]")
      ?: document.selectFirst("img[src*=verify]")
      ?: document.selectFirst("img[src*=captcha]")
      ?: document.selectFirst("img[src*=code]")
      ?: throw IllegalStateException("未找到验证码图片")
    val src = image.attr("src").ifBlank { image.absUrl("src") }
    if (src.isBlank()) {
      throw IllegalStateException("验证码图片地址为空")
    }
    return URL(URL(loginUrl), src).toString()
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

  private fun findFieldName(form: Element, selectors: List<String>): String? {
    selectors.forEach { selector ->
      val field = form.selectFirst(selector) ?: return@forEach
      val name = field.attr("name").trim()
      if (name.isNotBlank()) {
        return name
      }
      val id = field.id().trim()
      if (id.isNotBlank()) {
        return id
      }
    }
    return null
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

  companion object {
    private val USERNAME_SELECTORS = listOf(
      "#xh",
      "#username",
      "input[name='USERNAME']",
      "input[name='username']",
      "input[type='text']"
    )

    private val PASSWORD_SELECTORS = listOf(
      "#pwd",
      "#password",
      "input[name='PASSWORD']",
      "input[name='password']",
      "input[type='password']"
    )

    private val CAPTCHA_SELECTORS = listOf(
      "#SafeCode",
      "#RANDOMCODE",
      "input[name='RANDOMCODE']",
      "input[name='randomcode']",
      "input[name='captcha']"
    )
  }
}
