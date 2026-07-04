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

internal class HeadlessLoginClient(
  private val logger: (scope: String, status: String, message: String) -> Unit,
  private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
) {
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
    val cookies = linkedMapOf<String, String>()
    var loginDocument = fetchDocument(loginUrl, referer = null, cookies = cookies)
    logger("HEADLESS_LOGIN", "INFO", "已拉取登录页 title=${loginDocument.title().ifBlank { "-" }}")

    repeat(6) { attemptIndex ->
      val form = loginDocument.selectFirst("form") ?: throw IllegalStateException("未找到登录表单")
      val captchaUrl = resolveCaptchaUrl(loginDocument, loginUrl)
      val captchaBitmap = fetchBitmap(captchaUrl, referer = loginUrl, cookies = cookies)
      val captchaText = recognizeCaptcha(captchaBitmap)
        ?.replace(Regex("[^a-zA-Z0-9]"), "")
        ?.take(4)
        .orEmpty()

      if (captchaText.length != 4) {
        logger("HEADLESS_LOGIN", "WARN", "第 ${attemptIndex + 1} 次验证码识别失败：$captchaText")
        loginDocument = fetchDocument(loginUrl, referer = loginUrl, cookies = cookies)
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
        cookies = cookies,
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
        referer = loginUrl,
        cookies = cookies
      )
      val timetableDocument = parseHtml(timetableResponse.body, timetableUrl)
      if (looksLikeTimetableDocument(timetableDocument)) {
        logger("HEADLESS_LOGIN", "SUCCESS", "纯 HTTP 登录成功，验证码尝试次数=${attemptIndex + 1}")
        return LoginResult(
          cookies = cookies.toMap(),
          timetableHtml = timetableDocument.outerHtml(),
          captchaAttempts = attemptIndex + 1
        )
      }

      logger(
        "HEADLESS_LOGIN",
        "WARN",
        "第 ${attemptIndex + 1} 次登录后仍未进入课表页 title=${timetableDocument.title().ifBlank { "-" }}"
      )
      loginDocument = fetchDocument(loginUrl, referer = timetableUrl, cookies = cookies)
    }

    throw IllegalStateException("纯 HTTP 登录连续多次失败")
  }

  private fun fetchDocument(
    url: String,
    referer: String?,
    cookies: MutableMap<String, String>
  ): Document {
    val response = executeRequest(url = url, method = "GET", referer = referer, cookies = cookies)
    return parseHtml(response.body, url)
  }

  private fun fetchBitmap(
    url: String,
    referer: String?,
    cookies: MutableMap<String, String>
  ): Bitmap {
    val response = executeRequest(url = url, method = "GET", referer = referer, cookies = cookies)
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
    return if (action.isBlank()) loginUrl else URL(URL(loginUrl), action).toString()
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
    cookies: MutableMap<String, String>,
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
        if (cookies.isNotEmpty()) {
          setRequestProperty("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        }
      }

      if (currentMethod == "POST" && currentBody != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { output ->
          output.write(currentBody!!.toByteArray(Charsets.UTF_8))
        }
      }

      val code = connection.responseCode
      collectCookies(connection, cookies)
      val body = (if (code >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream)
        ?.use { it.readBytes() }
        ?: ByteArray(0)
      val location = connection.getHeaderField("Location")
      connection.disconnect()

      if (code in 300..399 && !location.isNullOrBlank()) {
        currentReferer = currentUrl
        currentUrl = URL(URL(currentUrl), location).toString()
        if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
          currentMethod = "GET"
          currentBody = null
        }
        logger("HEADLESS_HTTP", "INFO", "重定向到 $currentUrl code=$code")
        return@repeat
      }

      logger("HEADLESS_HTTP", "INFO", "$currentMethod $currentUrl code=$code cookieCount=${cookies.size}")
      return HttpResponse(code = code, body = body)
    }

    throw IllegalStateException("HTTP 重定向次数过多")
  }

  private fun collectCookies(connection: HttpURLConnection, cookies: MutableMap<String, String>) {
    connection.headerFields["Set-Cookie"].orEmpty().forEach { raw ->
      val firstPart = raw.substringBefore(';').trim()
      val separator = firstPart.indexOf('=')
      if (separator <= 0) return@forEach
      val name = firstPart.substring(0, separator).trim()
      val value = firstPart.substring(separator + 1).trim()
      if (name.isNotBlank()) {
        cookies[name] = value
      }
    }
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
    val html = document.outerHtml().lowercase()
    val title = document.title().lowercase()
    return html.contains("xskb") ||
      html.contains("课程表") ||
      title.contains("课表") ||
      document.select("table").isNotEmpty()
  }

  private fun encodeFormBody(parameters: Map<String, String>): String =
    parameters.entries.joinToString("&") { (key, value) ->
      "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

  private data class HttpResponse(
    val code: Int,
    val body: ByteArray
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
