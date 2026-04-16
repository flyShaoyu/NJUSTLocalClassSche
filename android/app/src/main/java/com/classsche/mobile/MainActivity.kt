package com.classsche.mobile

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ImageButton
import android.widget.ImageView
import com.classsche.mobile.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  private val mainHandler = Handler(Looper.getMainLooper())
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private val prefs by lazy { getSharedPreferences("classsche_prefs", Context.MODE_PRIVATE) }
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

  private var loginSubmitted = false
  private var cacheCaptureInProgress = false
  private var showingLiveTimetable = false
  private var currentWebScreen = WebScreen.HOME
  private var homePageLoaded = false
  private var currentAssetExportId: String? = null
  private var renderedHomeSignature: String? = null
  private var loginSessionBootstrapped = false

  private enum class WebScreen {
    HOME,
    TIMETABLE
  }

  companion object {
    private const val LOGIN_URL = "http://202.119.81.113:8080"
    private const val TIMETABLE_URL = "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do"
    private const val HOME_ASSET_BASE_URL = "file:///android_asset/"
    private const val GENERATED_HOME_HTML_FILE = "home-view-generated.html"
    private const val GENERATED_CACHE_HTML_FILE = "timetable-view-generated.html"
    private const val CACHE_JSON_FILE = "timetable.json"
    private const val CACHE_RAW_HTML_FILE = "timetable.raw.html"
    private const val PREF_USERNAME = "username"
    private const val PREF_PASSWORD = "password"
    private const val PREF_ASSET_EXPORT_ID = "asset_export_id"
    private const val CACHE_META_ASSET = "cache-meta.json"

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

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupToolbar()
    setupAuthWebView()
    setupHomeWebView()
    setupContentWebView()
    setupActions()
    restoreSavedCredentials()
    syncAssetExportId()

    showHomePage()
  }

  override fun onDestroy() {
    ioExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onBackPressed() {
    if (binding.timetablePage.visibility == View.VISIBLE && currentWebScreen != WebScreen.HOME) {
      showHomePage()
      return
    }

    if (binding.timetablePage.visibility == View.VISIBLE && binding.contentWebView.canGoBack()) {
      binding.contentWebView.goBack()
      return
    }

    super.onBackPressed()
  }

  private fun setupToolbar() {
    baseToolbarPaddingLeft = binding.toolbar.paddingLeft
    baseToolbarPaddingTop = binding.toolbar.paddingTop
    baseToolbarPaddingRight = binding.toolbar.paddingRight
    baseToolbarPaddingBottom = binding.toolbar.paddingBottom
    binding.toolbar.title = getString(R.string.toolbar_title_home)
    binding.toolbar.navigationIcon = null
    binding.toolbar.setNavigationOnClickListener {
      if (currentWebScreen == WebScreen.TIMETABLE) {
        showHomePage()
      }
    }
    ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
      lastStatusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      applyToolbarLayout()
      insets
    }
    applyToolbarLayout()
    updateToolbarNavigationButtonLayout()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupAuthWebView() {
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(binding.authWebView, true)

    with(binding.authWebView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      allowFileAccess = true
      allowContentAccess = true
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }

    binding.authWebView.webChromeClient = WebChromeClient()
    binding.authWebView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        updateStatus(getString(R.string.status_page_loaded, url))

        if (looksLikeTimetableUrl(url)) {
          showingLiveTimetable = true
          applyWebScreen(WebScreen.TIMETABLE)
          captureTimetablePage()
          return
        }

        if (looksLikeLoginUrl(url)) {
          if (!loginSubmitted) {
            fetchCaptchaFromWebView()
          }
          return
        }

        if (loginSubmitted) {
          loginSubmitted = false
          saveCredentials()
          updateStatus(getString(R.string.status_login_success))
          binding.authWebView.loadUrl(TIMETABLE_URL)
        }
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupHomeWebView() {
    with(binding.homeWebView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = true
      allowContentAccess = true
      builtInZoomControls = false
      displayZoomControls = false
      cacheMode = WebSettings.LOAD_NO_CACHE
    }

    binding.homeWebView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleInternalPageNavigation(request.url.toString())
      }

      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (url.contains("home-view", ignoreCase = true) || url.contains("home-from-json", ignoreCase = true)) {
          homePageLoaded = true
          renderedHomeSignature = currentHomeSignature()
          if (currentWebScreen == WebScreen.HOME) {
            applyWebScreen(WebScreen.HOME)
          }
        }
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupContentWebView() {
    with(binding.contentWebView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = true
      allowContentAccess = true
      builtInZoomControls = false
      displayZoomControls = false
      cacheMode = WebSettings.LOAD_NO_CACHE
    }

    binding.contentWebView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleInternalPageNavigation(request.url.toString())
      }

      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        when {
          url.contains("timetable-view", ignoreCase = true) || looksLikeTimetableUrl(url) -> {
            applyWebScreen(WebScreen.TIMETABLE)
          }
        }
      }
    }
  }

  private fun setupActions() {
    updateBottomNavSelection(binding.navHomeButton.id)
    binding.navHomeButton.setOnClickListener { showHomePage() }
    binding.navProfileButton.setOnClickListener { showLoginPage() }

    binding.openLoginButton.setOnClickListener {
      bootstrapLoginSession(forceReload = true)
    }

    binding.refreshCaptchaButton.setOnClickListener {
      refreshCaptchaInWebView()
    }

    binding.loginButton.setOnClickListener {
      submitLogin()
    }

    binding.openTimetableButton.setOnClickListener {
      showLiveTimetable()
      updateStatus(getString(R.string.status_opening_timetable))
    }

    binding.viewCacheButton.setOnClickListener {
      showCachedTimetable()
    }

  }

  private fun bootstrapLoginSession(forceReload: Boolean = false) {
    if (loginSessionBootstrapped && !forceReload) {
      return
    }

    loginSessionBootstrapped = true
    updateStatus(getString(R.string.status_loading_login))
    if (forceReload) {
      CookieManager.getInstance().removeSessionCookies(null)
      CookieManager.getInstance().flush()
    }
    binding.authWebView.loadUrl(LOGIN_URL)
  }

  private fun showLoginPage() {
    bootstrapLoginSession()
    clearInputFocus()
    binding.loginPage.visibility = View.VISIBLE
    binding.homeWebView.visibility = View.GONE
    binding.timetablePage.visibility = View.GONE
    binding.bottomNavGroup.visibility = View.VISIBLE
    updateBottomNavSelection(binding.navProfileButton.id)
    binding.toolbar.title = getString(R.string.toolbar_title_profile)
    binding.toolbar.navigationIcon = null
  }

  private fun applyWebScreen(screen: WebScreen) {
    currentWebScreen = screen
    dismissKeyboard()
    clearInputFocus()
    when (screen) {
      WebScreen.HOME -> {
        binding.loginPage.visibility = View.GONE
        binding.homeWebView.visibility = View.VISIBLE
        binding.timetablePage.visibility = View.GONE
      }
      WebScreen.TIMETABLE -> {
        binding.loginPage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.VISIBLE
      }
    }

    val showControls = screen == WebScreen.TIMETABLE
    binding.bottomNavGroup.visibility = if (showControls) View.GONE else View.VISIBLE
    if (!showControls) {
      updateBottomNavSelection(binding.navHomeButton.id)
    }
    binding.toolbar.title = if (showControls) {
      getString(R.string.toolbar_title_timetable)
    } else {
      getString(R.string.toolbar_title_home)
    }
    binding.toolbar.navigationIcon = if (showControls) {
      ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.mutate()?.apply {
        setTint(Color.WHITE)
      }
    } else {
      null
    }
    applyToolbarLayout()
    updateToolbarNavigationButtonLayout()
    if (screen == WebScreen.HOME) {
      binding.homeWebView.requestFocus()
    } else {
      binding.contentWebView.requestFocus()
    }
  }

  private fun applyToolbarLayout() {
    val extraBottom = if (currentWebScreen == WebScreen.HOME) dpToPx(10) else 0
    val minHeight = if (currentWebScreen == WebScreen.HOME) dpToPx(56) else 0

    binding.toolbar.minimumHeight = minHeight
    binding.toolbar.setPadding(
      baseToolbarPaddingLeft,
      baseToolbarPaddingTop + lastStatusBarInsetTop,
      baseToolbarPaddingRight,
      baseToolbarPaddingBottom + extraBottom
    )
  }

  private fun updateToolbarNavigationButtonLayout() {
    binding.toolbar.post {
      val targetSize = dpToPx(44)
      val horizontalMargin = dpToPx(8)
      for (index in 0 until binding.toolbar.childCount) {
        val child = binding.toolbar.getChildAt(index)
        if (child is ImageButton) {
          val params = child.layoutParams
          params.height = targetSize
          params.width = targetSize
          if (params is ViewGroup.MarginLayoutParams) {
            params.marginStart = horizontalMargin
            params.marginEnd = horizontalMargin
          }
          child.layoutParams = params
          child.minimumHeight = targetSize
          child.minimumWidth = targetSize
          child.setPadding(0, 0, 0, 0)
          child.scaleType = ImageView.ScaleType.CENTER
          child.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
      }
    }
  }

  private fun dpToPx(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      resources.displayMetrics
    ).toInt()

  private fun showHomePage() {
    showingLiveTimetable = false
    presentHomePage()
  }

  private fun showLiveTimetable() {
    showingLiveTimetable = true
    applyWebScreen(WebScreen.TIMETABLE)
    binding.contentWebView.loadUrl(TIMETABLE_URL)
  }

  private fun showCachedTimetable() {
    showingLiveTimetable = false
    applyWebScreen(WebScreen.TIMETABLE)
    showCachedTimetableOrEmpty()
  }

  private fun presentHomePage() {
    val targetSignature = currentHomeSignature()
    if (!homePageLoaded) {
      applyWebScreen(WebScreen.HOME)
    }

    if (homePageLoaded && renderedHomeSignature == targetSignature) {
      applyWebScreen(WebScreen.HOME)
      return
    }

    if (homePageLoaded && updateHomeRecentCoursesOnly()) {
      renderedHomeSignature = targetSignature
      applyWebScreen(WebScreen.HOME)
      return
    }

    loadCachedHome()
  }

  private fun loadCachedHome() {
    val homeCacheFile = File(filesDir, GENERATED_HOME_HTML_FILE)
    if (homeCacheFile.exists() && homeCacheFile.length() > 0L) {
      val cachedHtml = runCatching { homeCacheFile.readText(Charsets.UTF_8) }.getOrNull()
      if (!cachedHtml.isNullOrBlank()) {
        loadHomeHtml(cachedHtml)
        return
      }
    }

    val homeAssetHtml = runCatching {
      assets.open("home-view.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
    if (!homeAssetHtml.isNullOrBlank()) {
      loadHomeHtml(homeAssetHtml)
      return
    }

    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)
    if (cacheJsonFile.exists() && cacheJsonFile.length() > 2L) {
      val renderedHomeHtml = runCatching {
        TimetableRenderer.homeHtmlFromJson(this, cacheJsonFile.readText(Charsets.UTF_8))
      }.getOrNull()

      if (!renderedHomeHtml.isNullOrBlank()) {
        loadHomeHtml(renderedHomeHtml)
        return
      }
    }

    loadHomeHtml(TimetableRenderer.emptyHomeHtml(this))
  }

  private fun loadHomeHtml(html: String) {
    binding.homeWebView.stopLoading()
    binding.homeWebView.clearHistory()
    binding.homeWebView.clearCache(true)
    binding.homeWebView.loadDataWithBaseURL(
      HOME_ASSET_BASE_URL,
      html,
      "text/html",
      "utf-8",
      null
    )
  }

  private fun showCachedTimetableOrEmpty() {
    val cacheFile = File(filesDir, GENERATED_CACHE_HTML_FILE)
    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)

    if (!hasUsableTimetableCache(cacheFile, cacheJsonFile)) {
      showEmptyTimetablePage()
      return
    }

    loadGeneratedPage(
      webView = binding.contentWebView,
      cacheFile = cacheFile,
      fallbackBaseUrl = "https://classsche.local/fallback/",
      fallbackHtml = TimetableRenderer.emptyHtml(this)
    )
  }

  private fun showEmptyTimetablePage() {
    binding.contentWebView.stopLoading()
    binding.contentWebView.clearHistory()
    binding.contentWebView.clearCache(true)
    binding.contentWebView.loadDataWithBaseURL(
      "https://classsche.local/empty-timetable/",
      TimetableRenderer.emptyHtml(this),
      "text/html",
      "utf-8",
      null
    )
  }

  private fun hasUsableTimetableCache(cacheFile: File, cacheJsonFile: File): Boolean {
    if (!cacheFile.exists() || cacheFile.length() <= 0L) {
      return false
    }

    if (!cacheJsonFile.exists() || cacheJsonFile.length() <= 2L) {
      return false
    }

    return try {
      val raw = cacheJsonFile.readText(Charsets.UTF_8).trim()
      if (raw.isBlank() || raw == "[]") {
        false
      } else {
        JSONArray(raw).length() > 0
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun loadGeneratedPage(
    webView: WebView,
    cacheFile: File,
    fallbackBaseUrl: String,
    fallbackHtml: String
  ) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearCache(true)

    if (cacheFile.exists() && cacheFile.length() > 0L) {
      webView.loadUrl("file://${cacheFile.absolutePath}?v=${System.currentTimeMillis()}")
      return
    }

    webView.loadDataWithBaseURL(
      fallbackBaseUrl,
      fallbackHtml,
      "text/html",
      "utf-8",
      null
    )
  }

  private fun handleInternalPageNavigation(url: String): Boolean {
    return when {
      url.contains("timetable-view", ignoreCase = true) -> {
        showCachedTimetable()
        true
      }
      url.contains("home-view", ignoreCase = true) -> {
        showHomePage()
        true
      }
      else -> false
    }
  }

  private fun updateBottomNavSelection(selectedButtonId: Int) {
    val activeColor = Color.parseColor("#5B89BF")
    val inactiveColor = Color.parseColor("#7E8794")
    val buttons = listOf(binding.navHomeButton, binding.navProfileButton)

    buttons.forEach { button ->
      val selected = button.id == selectedButtonId
      button.setTextColor(if (selected) activeColor else inactiveColor)
      button.iconTint = android.content.res.ColorStateList.valueOf(if (selected) activeColor else inactiveColor)
      button.strokeWidth = 0
      button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
      button.rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#223A5E8C"))
      button.isChecked = selected
    }
  }

  private fun currentHomeSignature(): String {
    val homeCacheFile = File(filesDir, GENERATED_HOME_HTML_FILE)
    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)
    val homePart = if (homeCacheFile.exists()) "${homeCacheFile.length()}:${homeCacheFile.lastModified()}" else "missing"
    val jsonPart = if (cacheJsonFile.exists()) "${cacheJsonFile.length()}:${cacheJsonFile.lastModified()}" else "missing"
    return listOf(currentAssetExportId ?: "no-export", homePart, jsonPart).joinToString("|")
  }

  private fun updateHomeRecentCoursesOnly(): Boolean {
    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)
    if (!cacheJsonFile.exists() || cacheJsonFile.length() <= 2L) {
      return false
    }

    val rawJson = runCatching { cacheJsonFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
    val js = """
      (function() {
        if (window.ClassScheHome && typeof window.ClassScheHome.updateRecentCourses === 'function') {
          return window.ClassScheHome.updateRecentCourses(${JSONObject.quote(rawJson)});
        }
        return false;
      })();
    """.trimIndent()

    binding.homeWebView.evaluateJavascript(js, null)
    return true
  }

  private fun refreshCaptchaInWebView() {
    updateStatus(getString(R.string.status_refreshing_captcha))
    binding.authWebView.evaluateJavascript(
      """
      (function() {
        if (typeof ReShowCode === 'function') {
          ReShowCode();
        } else {
          var img = document.getElementById('SafeCodeImg');
          if (img) {
            img.click();
          }
        }
        return true;
      })();
      """.trimIndent()
    ) { _ ->
      mainHandler.postDelayed({ fetchCaptchaFromWebView() }, 500)
    }
  }

  private fun fetchCaptchaFromWebView() {
    binding.authWebView.evaluateJavascript(
      """
      (function() {
        var img = document.getElementById('SafeCodeImg');
        return img ? (img.getAttribute('src') || img.src || '') : '';
      })();
      """.trimIndent()
    ) { rawValue ->
      val relativeUrl = decodeJsValue(rawValue)
      if (relativeUrl.isBlank()) {
        updateStatus(getString(R.string.status_captcha_not_found))
        return@evaluateJavascript
      }

      loadCaptchaImage(relativeUrl)
    }
  }

  private fun loadCaptchaImage(relativeUrl: String) {
    val absoluteUrl = URL(URL(LOGIN_URL), relativeUrl).toString()
    val cookie = CookieManager.getInstance().getCookie(LOGIN_URL).orEmpty()

    ioExecutor.execute {
      try {
        val connection = URL(absoluteUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        if (cookie.isNotBlank()) {
          connection.setRequestProperty("Cookie", cookie)
        }

        BufferedInputStream(connection.inputStream).use { input ->
          val bitmap = BitmapFactory.decodeStream(input)
          mainHandler.post {
            binding.captchaImage.setImageBitmap(bitmap)
            binding.captchaImage.contentDescription = getString(R.string.captcha_loaded)
            updateStatus(getString(R.string.status_captcha_loaded))
          }
        }
      } catch (error: Exception) {
        mainHandler.post {
          updateStatus(getString(R.string.status_captcha_failed, error.message ?: "unknown"))
        }
      }
    }
  }

  private fun submitLogin() {
    val username = binding.usernameInput.editText?.text?.toString().orEmpty().trim()
    val password = binding.passwordInput.editText?.text?.toString().orEmpty().trim()
    val captcha = binding.captchaInput.editText?.text?.toString().orEmpty().trim()

    if (username.isBlank() || password.isBlank() || captcha.isBlank()) {
      updateStatus(getString(R.string.status_missing_fields))
      return
    }

    loginSubmitted = true
    updateStatus(getString(R.string.status_submitting_login))

    val script = """
      (function() {
        const setValue = (selectors, value) => {
          for (const selector of selectors) {
            const input = document.querySelector(selector);
            if (input) {
              input.value = value;
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              return true;
            }
          }
          return false;
        };

        const userOk = setValue(${toJsArray(USERNAME_SELECTORS)}, ${toJsString(username)});
        const passwordOk = setValue(${toJsArray(PASSWORD_SELECTORS)}, ${toJsString(password)});
        const captchaOk = setValue(${toJsArray(CAPTCHA_SELECTORS)}, ${toJsString(captcha)});

        const button = document.querySelector("input[type='submit'], button[type='submit'], #btnsubmit, .login_btn");
        const form = button ? button.form : document.querySelector("form");

        if (button) {
          button.click();
          return JSON.stringify({ userOk, passwordOk, captchaOk, submitted: true });
        }

        if (form) {
          form.submit();
          return JSON.stringify({ userOk, passwordOk, captchaOk, submitted: true });
        }

        return JSON.stringify({ userOk, passwordOk, captchaOk, submitted: false });
      })();
    """.trimIndent()

    binding.authWebView.evaluateJavascript(script) { result ->
      updateStatus(getString(R.string.status_submit_result, decodeJsValue(result)))
      mainHandler.postDelayed({
        if (binding.authWebView.url?.let(::looksLikeLoginUrl) == true) {
          loginSubmitted = false
          fetchCaptchaFromWebView()
        }
      }, 1200)
    }
  }

  private fun captureTimetablePage() {
    if (cacheCaptureInProgress) {
      return
    }

    cacheCaptureInProgress = true
    updateStatus("已进入课表页，正在抓取并更新本地缓存…")

    binding.authWebView.evaluateJavascript(
      """
      (function() {
        return document.documentElement ? document.documentElement.outerHTML : "";
      })();
      """.trimIndent()
    ) { rawValue ->
      val html = decodeJsValue(rawValue)
      if (html.isBlank()) {
        cacheCaptureInProgress = false
        updateStatus("课表页面抓取失败：HTML 为空。")
        return@evaluateJavascript
      }

      ioExecutor.execute {
        try {
          val courses = TimetableParser.parse(html)
          val renderedHomeHtml = TimetableRenderer.toHomeHtml(this@MainActivity, courses)
          val renderedHtml = TimetableRenderer.toHtml(this@MainActivity, courses)
          val json = TimetableRenderer.toJson(courses)

          File(filesDir, GENERATED_HOME_HTML_FILE).writeText(renderedHomeHtml, Charsets.UTF_8)
          File(filesDir, GENERATED_CACHE_HTML_FILE).writeText(renderedHtml, Charsets.UTF_8)
          File(filesDir, CACHE_JSON_FILE).writeText(json, Charsets.UTF_8)
          File(filesDir, CACHE_RAW_HTML_FILE).writeText(html, Charsets.UTF_8)

          mainHandler.post {
            cacheCaptureInProgress = false
            updateStatus("本地缓存已更新，共解析 ${courses.size} 条课程。")
            showCachedTimetable()
          }
        } catch (error: Exception) {
          mainHandler.post {
            cacheCaptureInProgress = false
            updateStatus("课表解析失败：${error.message ?: "unknown"}")
          }
        }
      }
    }
  }

  private fun updateStatus(message: String) {
    binding.statusText.text = message
  }

  private fun restoreSavedCredentials() {
    binding.usernameInput.editText?.setText(prefs.getString(PREF_USERNAME, "").orEmpty())
    binding.passwordInput.editText?.setText(prefs.getString(PREF_PASSWORD, "").orEmpty())
  }

  private fun clearInputFocus() {
    binding.usernameInput.editText?.clearFocus()
    binding.passwordInput.editText?.clearFocus()
    binding.captchaInput.editText?.clearFocus()
    currentFocus?.clearFocus()
  }

  private fun dismissKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    val token = currentFocus?.windowToken ?: binding.root.windowToken ?: return
    imm.hideSoftInputFromWindow(token, 0)
  }

  private fun saveCredentials() {
    val username = binding.usernameInput.editText?.text?.toString().orEmpty().trim()
    val password = binding.passwordInput.editText?.text?.toString().orEmpty().trim()
    if (username.isBlank() || password.isBlank()) return

    prefs.edit()
      .putString(PREF_USERNAME, username)
      .putString(PREF_PASSWORD, password)
      .apply()
  }

  private fun syncAssetExportId() {
    val currentExportId = readAssetExportId() ?: return
    currentAssetExportId = currentExportId
    val previousExportId = prefs.getString(PREF_ASSET_EXPORT_ID, null)

    if (previousExportId != null && previousExportId != currentExportId) {
      File(filesDir, GENERATED_HOME_HTML_FILE).delete()
    }

    prefs.edit()
      .putString(PREF_ASSET_EXPORT_ID, currentExportId)
      .apply()
  }

  private fun readAssetExportId(): String? {
    return try {
      assets.open(CACHE_META_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
        val json = JSONObject(reader.readText())
        json.optString("exportedAt").takeIf { it.isNotBlank() }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun looksLikeLoginUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) {
      return false
    }

    val lower = url.lowercase()
    return lower.contains("verifycode") ||
      lower.contains("login") ||
      lower.contains("index") ||
      lower.contains(":8080")
  }

  private fun looksLikeTimetableUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) {
      return false
    }

    val lower = url.lowercase()
    return lower.contains("xskb") || lower.contains("xskb_list")
  }

  private fun decodeJsValue(rawValue: String?): String {
    if (rawValue.isNullOrBlank() || rawValue == "null" || rawValue == "undefined") {
      return ""
    }

    return try {
      JSONArray("[$rawValue]").getString(0)
    } catch (_: Exception) {
      rawValue
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .trim()
    }
  }

  private fun toJsArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { toJsString(it) }

  private fun toJsString(value: String): String =
    "\"" + value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"") + "\""
}
