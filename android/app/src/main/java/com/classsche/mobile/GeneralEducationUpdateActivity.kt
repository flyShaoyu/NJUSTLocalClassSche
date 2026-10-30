package com.classsche.mobile

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivityGeneralEducationUpdateBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.Executors

/** A normal mobile WebView completes the school's checks; only its final DOM is read. */
class GeneralEducationUpdateActivity : AppCompatActivity() {
  companion object {
    const val REQUEST_CODE = 724
    fun expectedUrl(value: String): Boolean = runCatching {
      val uri = URI(value)
      uri.scheme == "https" && uri.host == "jwc.njust.edu.cn" && uri.port in listOf(-1, 443) &&
        uri.userInfo == null && uri.path in listOf("/b1/7f/c1189a307583/page.htm", "/b1/7f/c1189a307583/pagem.htm")
    }.getOrDefault(false)
  }
  private lateinit var binding: ActivityGeneralEducationUpdateBinding
  private val handler = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  @Volatile private var active = false
  private var foreground = false
  private var reading = false
  private var readSequence = 0
  private var saving = false
  @Volatile private var generation = 0
  private var remaining = 45000L
  private var started = 0L
  private var sawChallenge = false
  private val poll = Runnable { readPage() }
  // A navigation can discard evaluateJavascript's callback with its old document.
  private val readTimeout = Runnable {
    if (reading && active && !saving) { reading = false; readSequence++; scheduleRead() }
  }
  private val timeout = Runnable { fail(if (sawChallenge) "网站防护校验超时：未取得课程表正文，原分类表已保留" else "网页加载超时：未取得课程表正文，请检查网络，原分类表已保留") }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityGeneralEducationUpdateBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.toolbar.title = "更新通识分类表"
    SecondaryPageToolbar.bind(binding.root, binding.toolbar)
    binding.toolbar.setNavigationOnClickListener { finish() }
    with(binding.browser.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      setSupportZoom(true)
      builtInZoomControls = true
      displayZoomControls = false
      cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
      // Keep the real mobile WebView user agent and its own browser environment.
    }
    CookieManager.getInstance().setAcceptCookie(true)
    binding.browser.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || expectedUrl(request.url.toString())) return false
        fail("目标网站跳转错误：分类表离开预期教务处页面，原表已保留")
        return true
      }
      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        resetRead()
        if (active && url != "about:blank" && !expectedUrl(url)) fail("目标网站跳转错误：分类表地址不符，原表已保留")
        else if (active && foreground) scheduleRead()
      }
      override fun onPageCommitVisible(view: WebView, url: String) {
        if (active && foreground && expectedUrl(url)) scheduleRead(0)
      }
      override fun onPageFinished(view: WebView, url: String) {
        if (active && foreground && expectedUrl(url)) scheduleRead(0)
      }
      override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && active) fail("网站返回错误：HTTP ${response.statusCode}，校验未完成或网站拒绝访问，原分类表已保留")
      }
      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || !active) return
        val step = when (error.errorCode) {
          ERROR_TIMEOUT -> "网页连接超时"
          ERROR_HOST_LOOKUP -> "无法解析网站域名"
          ERROR_FAILED_SSL_HANDSHAKE -> "网站安全连接失败"
          ERROR_REDIRECT_LOOP -> "目标网站跳转错误"
          else -> "网页连接失败"
        }
        fail("$step（代码 ${error.errorCode}）：jwc.njust.edu.cn，原分类表已保留")
      }
    }
    binding.retry.setOnClickListener { start() }
    start()
  }

  private fun start() {
    if (active || saving) return
    generation++
    active = true; reading = false; sawChallenge = false; remaining = 45000L
    binding.retry.visibility = View.GONE; binding.progress.visibility = View.VISIBLE
    binding.status.text = "正在打开教务处分类表，等待网页完成校验…"
    AppDebugLog.append(this, "GENERAL_CATALOG", "INFO", "手机 WebView 开始读取公开分类表")
    binding.browser.loadUrl(GeneralEducationCatalogParser.SOURCE)
    if (foreground) resumeDeadline()
  }
  private fun scheduleRead(delay: Long = 600L) {
    handler.removeCallbacks(poll)
    if (active && foreground && !saving) handler.postDelayed(poll, delay)
  }
  private fun resetRead() {
    reading = false; readSequence++
    handler.removeCallbacks(readTimeout)
  }
  private fun readPage() {
    if (!active || !foreground || saving || reading) return
    if (!expectedUrl(binding.browser.url.orEmpty())) { scheduleRead(); return }
    reading = true
    val current = generation
    val read = ++readSequence
    handler.postDelayed(readTimeout, 3000)
    binding.browser.evaluateJavascript(GeneralEducationPageScript.READ_CATALOG) { raw ->
      if (!active || current != generation || read != readSequence || isFinishing || isDestroyed) return@evaluateJavascript
      handler.removeCallbacks(readTimeout)
      reading = false
      try {
        val decoded = JSONTokener(raw ?: "null").nextValue() as? String ?: error("网页未返回有效数据")
        val result = JSONObject(decoded)
        when (result.optString("state")) {
          "waiting" -> {
            sawChallenge = sawChallenge || result.optBoolean("challenge")
            binding.status.text = if (sawChallenge) "正在等待网站完成防护校验，取得完整课程表后自动保存…" else "网页正在加载，等待课程表正文…"
            scheduleRead()
          }
          "redirect" -> fail("目标网站跳转错误：未在校方分类表页面，原表已保留")
          "error" -> fail(result.optString("message", "分类表解析错误，原表已保留"))
          "ready" -> save(result.getString("html"), current)
          else -> fail("分类表解析错误：网页返回未知状态，原表已保留")
        }
      } catch (error: Exception) { fail("分类表解析错误：${error.message}，原表已保留") }
    }
  }
  private fun save(html: String, current: Int) {
    saving = true
    handler.removeCallbacks(poll); handler.removeCallbacks(timeout)
    binding.status.text = "课程表已加载，正在校验并保存到本机…"
    worker.execute {
      try {
        if (isFinishing || isDestroyed || current != generation) return@execute
        val catalog = GeneralEducationCatalogStore.save(this, html) { active && current == generation && !isFinishing && !isDestroyed }
        runOnUiThread {
          if (!active || current != generation || isFinishing || isDestroyed) return@runOnUiThread
          active = false; saving = false
          CookieManager.getInstance().flush()
          val count = catalog.getJSONArray("courses").length()
          AppDebugLog.append(this, "GENERAL_CATALOG", "INFO", "手机分类表更新成功，共 $count 条")
          Toast.makeText(this, "分类表已更新，共 $count 条", Toast.LENGTH_SHORT).show()
          setResult(RESULT_OK)
          finish()
        }
      } catch (error: Exception) { runOnUiThread { if (current == generation) { saving = false; fail(FailureDetails.describe(error)) } } }
    }
  }
  private fun fail(message: String) {
    if (!active || isFinishing || isDestroyed) return
    active = false; reading = false; saving = false; generation++
    handler.removeCallbacks(poll); handler.removeCallbacks(timeout); resetRead()
    binding.browser.stopLoading()
    binding.progress.visibility = View.GONE; binding.retry.visibility = View.VISIBLE
    binding.status.text = message
    AppDebugLog.append(this, "GENERAL_CATALOG", "FAIL", message)
  }
  private fun resumeDeadline() {
    started = SystemClock.elapsedRealtime()
    handler.removeCallbacks(timeout)
    handler.postDelayed(timeout, remaining.coerceAtLeast(1))
    scheduleRead(0)
  }
  override fun onResume() {
    super.onResume(); foreground = true; binding.browser.onResume()
    if (active && !saving) resumeDeadline()
  }
  override fun onPause() {
    if (foreground && active && !saving) remaining = (remaining - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)
    foreground = false
    handler.removeCallbacks(poll); handler.removeCallbacks(timeout); resetRead()
    binding.browser.onPause()
    super.onPause()
  }
  override fun onDestroy() {
    active = false; generation++
    handler.removeCallbacksAndMessages(null); worker.shutdownNow()
    binding.browser.stopLoading(); binding.browser.destroy()
    super.onDestroy()
  }
}
