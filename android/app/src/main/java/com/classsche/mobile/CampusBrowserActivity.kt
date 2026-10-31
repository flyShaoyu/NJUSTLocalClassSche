package com.classsche.mobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.view.MenuItem
import android.widget.Toast
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivityCampusToolsBinding
import java.net.URL

/** Shared WebView cookies, without any JavaScript bridge on remote pages. */
class CampusBrowserActivity : AppCompatActivity() {
  companion object {
    fun allowedUrl(value: String): Boolean = runCatching {
      val u = URL(value)
      u.userInfo == null && (u.protocol == "https" || u.protocol == "http")
    }.getOrDefault(false)
  }
  private val libraryPrivate by lazy { intent.getBooleanExtra("private_library",false) }
  private val libraryLogin by lazy { intent.getBooleanExtra("library_login",false) }
  private lateinit var binding: ActivityCampusToolsBinding
  @SuppressLint("SetJavaScriptEnabled") override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityCampusToolsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.toolbar.title = "常用网站"
    SecondaryPageToolbar.bind(binding.root, binding.toolbar,domainSubtitle=true)
    binding.toolbar.setNavigationOnClickListener { onBackPressed() }
    binding.toolbar.menu.add("复制链接").apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
    binding.toolbar.setOnMenuItemClickListener {
      val current = binding.toolsWebView.url.orEmpty()
      if (allowedUrl(current)) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网页链接", current))
        Toast.makeText(this, "已复制当前链接", Toast.LENGTH_SHORT).show()
      } else Toast.makeText(this, "当前没有可复制的网页链接", Toast.LENGTH_SHORT).show()
      true
    }
    with(binding.toolsWebView.settings) { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false; useWideViewPort = true; loadWithOverviewMode = true }
    if(intent.getBooleanExtra("desktop_mode",false)||android.net.Uri.parse(intent.getStringExtra("url").orEmpty()).host=="mail.njust.edu.cn"){
      binding.toolsWebView.settings.userAgentString=BrowserUserAgent.desktop(WebSettings.getDefaultUserAgent(this))
    }
    if (libraryPrivate) {
      with(binding.toolsWebView.settings) { cacheMode=WebSettings.LOAD_NO_CACHE; saveFormData=false; domStorageEnabled=false }
      binding.toolsWebView.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    binding.toolsWebView.webChromeClient = object : WebChromeClient() {
      override fun onReceivedTitle(view: WebView, title: String?) { binding.toolbar.title = title?.take(40)?.ifBlank { "常用网站" } ?: "常用网站" }
    }
    binding.toolsWebView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !allowedUrl(request.url.toString())
      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { binding.browserError.visibility = View.GONE; binding.toolbar.subtitle = runCatching { URL(url).host }.getOrDefault("") }
      override fun onPageFinished(view: WebView, url: String) {
        if (libraryPrivate) CookieManager.getInstance().flush()
        if (!libraryLogin || !url.startsWith(CampusDataClient.LIBRARY+"reader/")) return
        // Read only the page's logged-in marker; no credentials or page content are returned.
        view.evaluateJavascript("Array.from(document.querySelectorAll('a[href]')).some(a=>/logout/i.test(a.getAttribute('href')||'')&&/注销|退出/.test(a.textContent))") { result ->
          if (result=="true" && !isFinishing && !isDestroyed) { setResult(RESULT_OK);finish() }
        }
      }
      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
          val type = when (error.errorCode) {
            ERROR_TIMEOUT -> "网页连接超时"
            ERROR_HOST_LOOKUP -> "无法解析网站域名"
            ERROR_FAILED_SSL_HANDSHAKE -> "网站安全连接失败"
            ERROR_REDIRECT_LOOP -> "目标网站跳转错误"
            else -> "网页连接失败"
          }
          showError("$type：${error.description}（代码 ${error.errorCode}）")
        }
      }
      override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) showError("网站返回错误：HTTP ${response.statusCode}")
      }
    }
    val url = intent.getStringExtra("url").orEmpty()
    if (allowedUrl(url)) binding.toolsWebView.loadUrl(url) else showError("网页地址无效")
  }
  private fun showError(message: String) { binding.browserError.text = message; binding.browserError.visibility = View.VISIBLE }
  @Deprecated("Deprecated in Java") override fun onBackPressed() { if (binding.toolsWebView.canGoBack()) binding.toolsWebView.goBack() else finish() }
  override fun onDestroy() { binding.toolsWebView.stopLoading(); binding.toolsWebView.destroy(); super.onDestroy() }
}
