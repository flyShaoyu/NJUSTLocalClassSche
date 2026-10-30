package com.classsche.mobile

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object TeachingEvaluationClient {
  fun create(context: Context): TeachingEvaluationEngine {
    val app = context.applicationContext
    val cookieStore = WebViewLoginCookieStore
    val account = CredentialStore.username(app)
    val prefs = app.getSharedPreferences("evaluation_uncertain_" + digest(account), Context.MODE_PRIVATE)
    val journal = object : EvaluationJournal {
      override fun uncertain(key: String) = prefs.contains(key)
      override fun markUncertain(key: String) {
        check(prefs.edit().putLong(key, System.currentTimeMillis()).commit()) { "本机提交记录保存失败，未发送评价" }
      }
      override fun confirmed(key: String) {
        if (prefs.contains(key)) check(prefs.edit().remove(key).commit()) { "校方已确认评价，但本机记录清理失败，请核实后再操作" }
      }
    }
    return TeachingEvaluationEngine(Http(cookieStore), journal, ensureSession = {
      try {
        HeadlessLoginClient({ scope, state, message -> AppDebugLog.append(app, scope, state, message) })
          .login(UniversityEndpoints.LOGIN, UniversityEndpoints.TIMETABLE, "", "")
      } catch(error: Exception) {
        if(error.message.orEmpty().contains("未保存账号和密码"))throw CampusLoginRequired("登录状态已失效，请先在登录页完成认证")
        throw error
      }
    }, sessionKey = {
      digest(cookieStore.read(TeachingEvaluationParser.ENTRY).split(';').find { it.trim().startsWith("JSESSIONID=") }.orEmpty())
    })
  }
  private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

  private class Http(private val cookies: LoginCookieStore) : EvaluationTransport {
    private var lastRequest = 0L
    override fun get(url: String): Document = request(url, null, "UTF-8")
    override fun post(url: String, fields: List<Pair<String, String>>, charset: String): Document = request(url, fields, charset)
    private fun request(initial: String, fields: List<Pair<String, String>>?, charset: String): Document {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
      var url = initial
      var body = fields?.joinToString("&") { (key, value) -> URLEncoder.encode(key, charset) + "=" + URLEncoder.encode(value, charset) }
      fun timeout(): Int {
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0 || Thread.currentThread().isInterrupted) throw java.net.SocketTimeoutException("评教请求超过 45 秒")
        return left.coerceIn(1,15000).toInt()
      }
      repeat(8) {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastRequest)
        if (lastRequest != 0L && elapsed < 500L) Thread.sleep(500L-elapsed)
        lastRequest = System.nanoTime()
        check(TeachingEvaluationParser.allowed(url)) { "目标网站跳转错误：评教请求离开预期教务地址" }
        val target = URL(url)
        val connection = target.openConnection() as HttpURLConnection
        try {
          connection.instanceFollowRedirects = false
          connection.useCaches = false
          connection.connectTimeout = timeout(); connection.readTimeout = timeout()
          connection.requestMethod = if (body == null) "GET" else "POST"
          connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36")
          connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
          connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
          connection.setRequestProperty("Referer", TeachingEvaluationParser.ENTRY)
          cookies.read(url).takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
          if (body != null) {
            val encoded=body!!.toByteArray(Charsets.US_ASCII)
            connection.doOutput = true
            // An unbuffered fixed-length body cannot be replayed after an I/O failure.
            connection.setFixedLengthStreamingMode(encoded.size)
            connection.setRequestProperty("Origin", UniversityEndpoints.ORIGIN)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=$charset")
            connection.outputStream.use { it.write(encoded) }
          }
          val status = connection.responseCode
          cookies.receive(url, connection.headerFields.filterKeys { it != null })
          if (status in listOf(301,302,303,307,308)) {
            // Following a 307/308 would resend a POST. Leave the outcome uncertain instead.
            check(body == null || status !in listOf(307,308)) { "评教提交返回 HTTP $status，需要重复发送表单；已停止，结果待核实" }
            val location = connection.getHeaderField("Location") ?: error("目标网站跳转错误：HTTP $status 缺少 Location")
            val next = URL(target,location)
            if (next.host == "ids.njust.edu.cn") throw CampusLoginRequired("登录状态已失效，请重新登录；本次未自动提交账号密码")
            url = TeachingEvaluationParser.resolve(url, location)
            if (status in listOf(301,302,303)) body = null
          } else {
            if (status in listOf(401,403)) throw CampusLoginRequired("评教访问被拒绝：HTTP $status，请重新登录或在学校网页检查权限")
            check(status in 200..299) { "网站返回错误：HTTP $status，${target.host}${target.path}" }
            val bytes = connection.inputStream.use { input ->
              val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
              while(true) {
                connection.readTimeout=timeout()
                val count=input.read(buffer); if(count<0)break
                check(output.size()+count<=4*1024*1024) { "评教页面内容超过限制，未继续处理" }
                output.write(buffer,0,count)
              }
              output.toByteArray()
            }
            val document=Jsoup.parse(ByteArrayInputStream(bytes),null,url)
            if(document.selectFirst("input[type=password]")!=null)throw CampusLoginRequired("教务登录状态已失效，请先完成认证")
            cookies.flush()
            return document
          }
        } finally { connection.disconnect() }
      }
      error("目标网站跳转错误：评教请求重定向次数过多")
    }
  }
}
