package com.classsche.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.classsche.mobile.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.abs

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
  private data class HomeImageAsset(
    val caption: String,
    val thumbAssetPath: String,
    val detailAssetPath: String,
    val fullAssetPath: String
  )
  private data class HomeViewerTransformState(
    val scale: Float,
    val panX: Float,
    val panY: Float,
    val useFullResolution: Boolean
  )
  private var currentHomeImages: List<HomeImageAsset> = emptyList()
  private var currentHomeImageIndex = 0
  private var homeImageTouchStartX: Float? = null
  private var homeImageTouchStartY: Float? = null
  private var homeImageTapMoved = false
  private var homeImageGestureLockedHorizontal = false
  private var homeImageTrackOffset = 0f
  private var homeImageVelocityTracker: VelocityTracker? = null
  private var homeImageAnimator: AnimatorSet? = null
  private var homeViewerVisible = false
  private var homeViewerActiveIndex = 0
  private var homeViewerScale = 1f
  private var homeViewerPanX = 0f
  private var homeViewerPanY = 0f
  private var homeViewerLastTouchX = 0f
  private var homeViewerLastTouchY = 0f
  private var homeViewerTouchStartX: Float? = null
  private var homeViewerTouchStartY: Float? = null
  private var homeViewerGestureLockedHorizontal = false
  private var homeViewerTrackOffset = 0f
  private var homeViewerDragging = false
  private var homeViewerActivePointerId = MotionEvent.INVALID_POINTER_ID
  private var homeViewerVelocityTracker: VelocityTracker? = null
  private var homeViewerInertiaAnimator: ValueAnimator? = null
  private var homeViewerCurrentAssetPath: String? = null
  private val homeViewerMatrix = Matrix()
  private val homeBitmapCache = object : LruCache<String, Bitmap>(12) {}
  private val homeViewerTransitionStates = mutableMapOf<String, HomeViewerTransformState>()
  private lateinit var homeViewerScaleDetector: ScaleGestureDetector
  private val homeCarouselRunnable = object : Runnable {
    override fun run() {
      if (currentWebScreen == WebScreen.HOME && binding.homePage.visibility == View.VISIBLE && currentHomeImages.size > 1) {
        showNextHomeImage(animated = true)
      }
      mainHandler.postDelayed(this, 4200)
    }
  }

  private data class HomeMenuEntry(
    val key: String,
    val label: String,
    val iconRes: Int,
    val enabled: Boolean
  )

  private data class HomeRecentEntry(
    val displayDay: String,
    val title: String,
    val meta: String,
    val room: String,
    val isToday: Boolean,
    val isAlert: Boolean
  )

  private data class NormalizedCourse(
    val course: TimetableCourse,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>
  )

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
    private val HOME_MENU_ITEMS = listOf(
      HomeMenuEntry("exam", "考试安排", android.R.drawable.ic_menu_agenda, false),
      HomeMenuEntry("score", "成绩查询", android.R.drawable.ic_menu_sort_by_size, false),
      HomeMenuEntry("level", "等级考试", android.R.drawable.ic_menu_edit, false),
      HomeMenuEntry("add", "添加课表", android.R.drawable.ic_menu_add, false),
      HomeMenuEntry("schedule", "课表查询", android.R.drawable.ic_menu_today, true),
      HomeMenuEntry("room", "空闲教室", android.R.drawable.ic_menu_my_calendar, false),
      HomeMenuEntry("site", "常用网站", android.R.drawable.ic_menu_compass, false),
      HomeMenuEntry("refresh", "更新课表", android.R.drawable.ic_popup_sync, false),
      HomeMenuEntry("library", "图书搜索", android.R.drawable.ic_menu_search, false),
      HomeMenuEntry("borrow", "借阅信息", android.R.drawable.ic_menu_info_details, false)
    )
    private val HOME_WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    private val HOME_WEEK_TITLES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val HOME_ANCHOR_WEEK = 6
    private val HOME_ANCHOR_MONDAY: LocalDate = LocalDate.of(2026, 4, 6)
    private val PERIOD_SLOTS = mapOf(
      1 to ("08:00" to "08:45"),
      2 to ("08:50" to "09:35"),
      3 to ("09:40" to "10:25"),
      4 to ("10:40" to "11:25"),
      5 to ("11:30" to "12:15"),
      6 to ("14:00" to "14:45"),
      7 to ("14:50" to "15:35"),
      8 to ("15:50" to "16:35"),
      9 to ("16:40" to "17:25"),
      10 to ("17:30" to "18:15"),
      11 to ("19:00" to "19:45"),
      12 to ("19:50" to "20:35"),
      13 to ("20:40" to "21:25"),
      14 to ("12:15" to "14:00")
    )

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
    setupHomePage()
    setupHomeImageViewer()
    setupAuthWebView()
    setupHomeWebView()
    setupContentWebView()
    setupActions()
    restoreSavedCredentials()
    syncAssetExportId()

    showHomePage()
  }

  override fun onDestroy() {
    mainHandler.removeCallbacks(homeCarouselRunnable)
    homeImageVelocityTracker?.recycle()
    homeImageVelocityTracker = null
    homeViewerVelocityTracker?.recycle()
    homeViewerVelocityTracker = null
    homeViewerInertiaAnimator?.cancel()
    homeViewerInertiaAnimator = null
    ioExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onBackPressed() {
    if (homeViewerVisible) {
      closeHomeImageViewer()
      return
    }

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
    closeHomeImageViewer(resumeCarousel = false)
    bootstrapLoginSession()
    clearInputFocus()
    binding.loginPage.visibility = View.VISIBLE
    binding.homePage.visibility = View.GONE
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
    if (screen != WebScreen.HOME) {
      closeHomeImageViewer(resumeCarousel = false)
    }
    when (screen) {
      WebScreen.HOME -> {
        binding.loginPage.visibility = View.GONE
        binding.homePage.visibility = View.VISIBLE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.GONE
      }
      WebScreen.TIMETABLE -> {
        binding.loginPage.visibility = View.GONE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.VISIBLE
      }
    }

    val showControls = screen == WebScreen.TIMETABLE
    binding.bottomNavGroup.visibility = if (showControls || homeViewerVisible) View.GONE else View.VISIBLE
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
      binding.homePage.requestFocus()
      mainHandler.removeCallbacks(homeCarouselRunnable)
      if (currentHomeImages.size > 1) {
        mainHandler.postDelayed(homeCarouselRunnable, 4200)
      }
    } else {
      binding.contentWebView.requestFocus()
      mainHandler.removeCallbacks(homeCarouselRunnable)
    }
  }

  private fun setupHomePage() {
    binding.homeImagePrevButton.setOnClickListener {
      val itemCount = currentHomeImages.size
      if (itemCount <= 1) return@setOnClickListener
      mainHandler.removeCallbacks(homeCarouselRunnable)
      showPreviousHomeImage(animated = true)
      mainHandler.postDelayed(homeCarouselRunnable, 4200)
    }
    binding.homeImageNextButton.setOnClickListener {
      val itemCount = currentHomeImages.size
      if (itemCount <= 1) return@setOnClickListener
      mainHandler.removeCallbacks(homeCarouselRunnable)
      showNextHomeImage(animated = true)
      mainHandler.postDelayed(homeCarouselRunnable, 4200)
    }
    binding.homeImageViewport.setOnTouchListener { _, event ->
      homeImageVelocityTracker?.addMovement(event)
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          homeImageAnimator?.cancel()
          homeImageAnimator = null
          homeImageTouchStartX = event.x
          homeImageTouchStartY = event.y
          homeImageTapMoved = false
          homeImageGestureLockedHorizontal = false
          homeImageTrackOffset = 0f
          homeImageVelocityTracker?.recycle()
          homeImageVelocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val startX = homeImageTouchStartX
          val startY = homeImageTouchStartY
          if (startX != null && startY != null) {
            val deltaX = event.x - startX
            val deltaY = event.y - startY
            if (abs(deltaX) >= dpToPx(4) || abs(deltaY) >= dpToPx(4)) {
              homeImageTapMoved = true
            }
            if (!homeImageGestureLockedHorizontal && abs(deltaX) >= dpToPx(6) && abs(deltaX) >= abs(deltaY) * 0.65f) {
              homeImageGestureLockedHorizontal = true
              binding.homePage.requestDisallowInterceptTouchEvent(true)
            }
            if (homeImageGestureLockedHorizontal) {
              applyHomeImageTrackOffset(deltaX)
            }
            return@setOnTouchListener homeImageGestureLockedHorizontal
          }
          false
        }
        MotionEvent.ACTION_UP -> {
          val startX = homeImageTouchStartX
          val startY = homeImageTouchStartY
          homeImageTouchStartX = null
          homeImageTouchStartY = null
          binding.homePage.requestDisallowInterceptTouchEvent(false)
          if (startX != null && !homeImageGestureLockedHorizontal && !homeImageTapMoved && currentHomeImages.isNotEmpty()) {
            openHomeImageViewer(currentHomeImageIndex)
            homeImageVelocityTracker?.recycle()
            homeImageVelocityTracker = null
            return@setOnTouchListener true
          }
          if (startX != null && currentHomeImages.size > 1) {
            val delta = event.x - startX
            val deltaY = if (startY != null) event.y - startY else 0f
            if (homeImageGestureLockedHorizontal && abs(delta) >= dpToPx(14) && abs(delta) >= abs(deltaY) * 0.65f) {
              mainHandler.removeCallbacks(homeCarouselRunnable)
              val tracker = homeImageVelocityTracker
              tracker?.computeCurrentVelocity(1000)
              val velocityX = tracker?.xVelocity ?: 0f
              finishHomeImageDrag(delta, velocityX)
              mainHandler.postDelayed(homeCarouselRunnable, 4200)
              homeImageGestureLockedHorizontal = false
              homeImageVelocityTracker?.recycle()
              homeImageVelocityTracker = null
              return@setOnTouchListener true
            }
          }
          animateHomeImageOffsetTo(0f, null)
          homeImageGestureLockedHorizontal = false
          homeImageVelocityTracker?.recycle()
          homeImageVelocityTracker = null
          false
        }
        MotionEvent.ACTION_CANCEL -> {
          homeImageTouchStartX = null
          homeImageTouchStartY = null
          homeImageGestureLockedHorizontal = false
          binding.homePage.requestDisallowInterceptTouchEvent(false)
          animateHomeImageOffsetTo(0f, null)
          homeImageVelocityTracker?.recycle()
          homeImageVelocityTracker = null
          false
        }
        else -> false
      }
    }
    binding.homeOpenTimetableButton.setOnClickListener { showCachedTimetable() }
    renderHomeMenu()
  }

  private fun setupHomeImageViewer() {
    homeViewerScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (!homeViewerVisible) return false
        val previousScale = homeViewerScale
        val nextScale = (previousScale * detector.scaleFactor).coerceIn(1f, 4f)
        if (abs(nextScale - previousScale) < 0.001f) return true
        val ratio = nextScale / previousScale
        homeViewerScale = nextScale
        homeViewerPanX *= ratio
        homeViewerPanY *= ratio
        maybeUpgradeHomeImageViewerResolution()
        applyHomeImageViewerMatrix()
        return true
      }
    })

    binding.homeImageViewerOverlay.setOnClickListener {
      closeHomeImageViewer()
    }
    binding.homeImageViewerStage.setOnClickListener {
      // Keep clicks inside the viewer from closing the overlay.
    }
    binding.homeImageViewerCloseButton.setOnClickListener {
      closeHomeImageViewer()
    }
    binding.homeImageViewerPrevButton.setOnClickListener {
      showHomeImageViewerStep(-1)
    }
    binding.homeImageViewerNextButton.setOnClickListener {
      showHomeImageViewerStep(1)
    }
    binding.homeImageViewerStage.setOnTouchListener { _, event ->
      if (!homeViewerVisible) {
        return@setOnTouchListener false
      }
      homeViewerVelocityTracker?.addMovement(event)
      homeViewerScaleDetector.onTouchEvent(event)
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          homeViewerInertiaAnimator?.cancel()
          homeViewerInertiaAnimator = null
          homeViewerVelocityTracker?.recycle()
          homeViewerVelocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
          homeViewerActivePointerId = event.getPointerId(0)
          homeViewerLastTouchX = event.getX(0)
          homeViewerLastTouchY = event.getY(0)
          homeViewerTouchStartX = event.getX(0)
          homeViewerTouchStartY = event.getY(0)
          homeViewerGestureLockedHorizontal = false
          homeViewerTrackOffset = 0f
          homeViewerDragging = false
          true
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
          homeViewerInertiaAnimator?.cancel()
          homeViewerInertiaAnimator = null
          val pointerIndex = event.actionIndex
          homeViewerActivePointerId = event.getPointerId(pointerIndex)
          homeViewerLastTouchX = event.getX(pointerIndex)
          homeViewerLastTouchY = event.getY(pointerIndex)
          homeViewerTouchStartX = event.getX(pointerIndex)
          homeViewerTouchStartY = event.getY(pointerIndex)
          homeViewerGestureLockedHorizontal = false
          homeViewerDragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val pointerIndex = event.findPointerIndex(homeViewerActivePointerId).takeIf { it >= 0 } ?: 0
          val currentX = event.getX(pointerIndex)
          val currentY = event.getY(pointerIndex)
          val deltaX = currentX - homeViewerLastTouchX
          val deltaY = currentY - homeViewerLastTouchY
          homeViewerLastTouchX = currentX
          homeViewerLastTouchY = currentY
          if (homeViewerScaleDetector.isInProgress) {
            homeViewerDragging = false
            return@setOnTouchListener true
          }
          val startX = homeViewerTouchStartX
          val startY = homeViewerTouchStartY
          if (currentHomeImages.size > 1 && startX != null && startY != null) {
            val totalDeltaX = currentX - startX
            val totalDeltaY = currentY - startY
            if (homeViewerScale <= 1f) {
              if (!homeViewerGestureLockedHorizontal && abs(totalDeltaX) >= dpToPx(6) && abs(totalDeltaX) >= abs(totalDeltaY) * 0.65f) {
                homeViewerGestureLockedHorizontal = true
              }
            } else if (!homeViewerGestureLockedHorizontal && shouldStartZoomedHomeViewerSwipe(totalDeltaX, totalDeltaY)) {
              homeViewerGestureLockedHorizontal = true
              homeViewerTrackOffset = 0f
            }
            if (homeViewerGestureLockedHorizontal) {
              if (homeViewerScale <= 1f) {
                applyHomeImageViewerTrackOffset(totalDeltaX)
              } else {
                homeViewerPanX = clampHomeViewerPanX(homeViewerPanX + deltaX)
                homeViewerPanY = clampHomeViewerPanY(homeViewerPanY)
                applyHomeImageViewerTrackOffset(homeViewerTrackOffset + deltaX)
              }
              return@setOnTouchListener true
            }
          }
          if (homeViewerScale > 1f) {
            if (!homeViewerDragging && (abs(deltaX) >= dpToPx(1) || abs(deltaY) >= dpToPx(1))) {
              homeViewerDragging = true
            }
            if (homeViewerDragging) {
              homeViewerPanX += deltaX
              homeViewerPanY += deltaY
              applyHomeImageViewerMatrix()
            }
          }
          true
        }
        MotionEvent.ACTION_POINTER_UP -> {
          val liftedPointerId = event.getPointerId(event.actionIndex)
          if (liftedPointerId == homeViewerActivePointerId) {
            val replacementIndex = if (event.actionIndex == 0) 1 else 0
            if (replacementIndex < event.pointerCount) {
              homeViewerActivePointerId = event.getPointerId(replacementIndex)
              homeViewerLastTouchX = event.getX(replacementIndex)
              homeViewerLastTouchY = event.getY(replacementIndex)
            } else {
              homeViewerActivePointerId = MotionEvent.INVALID_POINTER_ID
            }
          }
          homeViewerTouchStartX = null
          homeViewerTouchStartY = null
          homeViewerGestureLockedHorizontal = false
          homeViewerDragging = false
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (event.actionMasked == MotionEvent.ACTION_UP && homeViewerGestureLockedHorizontal && currentHomeImages.size > 1) {
            homeViewerVelocityTracker?.computeCurrentVelocity(1000)
            val velocityX = homeViewerVelocityTracker?.xVelocity ?: 0f
            finishHomeImageViewerDrag(homeViewerTrackOffset, velocityX)
          } else if (event.actionMasked == MotionEvent.ACTION_UP && homeViewerScale > 1f) {
            homeViewerVelocityTracker?.computeCurrentVelocity(1000)
            val velocityX = homeViewerVelocityTracker?.xVelocity ?: 0f
            val velocityY = homeViewerVelocityTracker?.yVelocity ?: 0f
            startHomeImageViewerInertia(velocityX, velocityY)
          } else {
            animateHomeImageViewerOffsetTo(0f, null)
          }
          homeViewerActivePointerId = MotionEvent.INVALID_POINTER_ID
          homeViewerTouchStartX = null
          homeViewerTouchStartY = null
          homeViewerGestureLockedHorizontal = false
          homeViewerDragging = false
          homeViewerVelocityTracker?.recycle()
          homeViewerVelocityTracker = null
          true
        }
        else -> true
      }
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
    if (homePageLoaded && renderedHomeSignature == targetSignature) {
      applyWebScreen(WebScreen.HOME)
      return
    }
    renderNativeHome()
    renderedHomeSignature = targetSignature
    homePageLoaded = true
    applyWebScreen(WebScreen.HOME)
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

  private fun renderNativeHome() {
    currentHomeImages = loadHomeImages()
    currentHomeImageIndex = currentHomeImageIndex.coerceIn(0, max(0, currentHomeImages.lastIndex))
    setHomeImageIndex(currentHomeImageIndex)

    val courses = loadCoursesFromCacheJson()
    val recentItems = buildRecentCourses(courses)
    binding.homeRecentHint.text = if (recentItems.isEmpty()) {
      getString(R.string.home_recent_empty)
    } else {
      getString(R.string.home_recent_updated)
    }
    renderRecentCourses(if (recentItems.isEmpty()) listOf(
      HomeRecentEntry("空", "最近没有待上的课程", "可以点击下方按钮查看完整课表", "待定", false, false)
    ) else recentItems)
  }

  private fun loadHomeImages(): List<HomeImageAsset> {
    val html = runCatching {
      assets.open("home-view.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull().orEmpty()
    val match = Regex("""const images = (\[.*?]);""", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)
      ?: return emptyList()
    val rawArray = match.groupValues.getOrNull(1) ?: return emptyList()
    val array = runCatching { JSONArray(rawArray) }.getOrNull() ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val caption = item.optString("caption")
        val thumb = item.optString("src").removePrefix("./")
        val detail = item.optString("detailSrc").removePrefix("./").ifBlank { thumb }
        val full = item.optString("fullSrc").removePrefix("./").ifBlank { detail }
        add(HomeImageAsset(caption, thumb, detail, full))
      }
    }
  }

  private fun updateHomeGalleryUi(position: Int) {
    val current = currentHomeImages.getOrNull(position)
    binding.homeImageCaption.text = current?.caption.orEmpty()
    binding.homeImagePrevButton.visibility = if (currentHomeImages.size > 1) View.VISIBLE else View.GONE
    binding.homeImageNextButton.visibility = if (currentHomeImages.size > 1) View.VISIBLE else View.GONE
    renderHomeDots(position)
  }

  private fun setHomeImageIndex(index: Int) {
    if (currentHomeImages.isEmpty()) {
      currentHomeImageIndex = 0
      loadHomeImageInto(binding.homeImagePrevView, null)
      loadHomeImageInto(binding.homeImageCurrentView, null)
      loadHomeImageInto(binding.homeImageNextView, null)
      updateHomeGalleryUi(0)
      return
    }
    currentHomeImageIndex = index.coerceIn(0, max(0, currentHomeImages.lastIndex))
    val width = binding.homeImageViewport.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    val current = currentHomeImages.getOrNull(currentHomeImageIndex)
    val previous = currentHomeImages.getOrNull((currentHomeImageIndex - 1 + currentHomeImages.size) % currentHomeImages.size)
    val next = currentHomeImages.getOrNull((currentHomeImageIndex + 1) % currentHomeImages.size)
    loadHomeImageInto(binding.homeImageCurrentView, current?.thumbAssetPath)
    loadHomeImageInto(binding.homeImagePrevView, previous?.thumbAssetPath)
    loadHomeImageInto(binding.homeImageNextView, next?.thumbAssetPath)
    homeImageTrackOffset = 0f
    binding.homeImagePrevView.translationX = -width.toFloat()
    binding.homeImageCurrentView.translationX = 0f
    binding.homeImageNextView.translationX = width.toFloat()
    binding.homeImagePrevView.alpha = 1f
    binding.homeImageCurrentView.alpha = 1f
    binding.homeImageNextView.alpha = 1f
    updateHomeGalleryUi(currentHomeImageIndex)
  }

  private fun openHomeImageViewer(index: Int) {
    if (currentHomeImages.isEmpty()) return
    homeViewerVisible = true
    homeViewerActiveIndex = index.coerceIn(0, max(0, currentHomeImages.lastIndex))
    homeViewerScale = 1f
    homeViewerPanX = 0f
    homeViewerPanY = 0f
    homeViewerTrackOffset = 0f
    homeViewerCurrentAssetPath = null
    mainHandler.removeCallbacks(homeCarouselRunnable)
    binding.bottomNavGroup.visibility = View.GONE
    binding.homeImageViewerOverlay.visibility = View.VISIBLE
    binding.homeImageViewerOverlay.bringToFront()
    renderHomeImageViewer(useFullResolution = false, resetTransform = true, sourceIndex = null)
    preloadHomeViewerAssets(homeViewerActiveIndex)
  }

  private fun closeHomeImageViewer(resumeCarousel: Boolean = true) {
    if (!homeViewerVisible && binding.homeImageViewerOverlay.visibility != View.VISIBLE) {
      return
    }
    homeViewerTransitionStates.clear()
    homeViewerInertiaAnimator?.cancel()
    homeViewerInertiaAnimator = null
    homeViewerVelocityTracker?.recycle()
    homeViewerVelocityTracker = null
    homeViewerVisible = false
    homeViewerDragging = false
    homeViewerScale = 1f
    homeViewerPanX = 0f
    homeViewerPanY = 0f
    homeViewerTrackOffset = 0f
    homeViewerCurrentAssetPath = null
    binding.homeImageViewerOverlay.visibility = View.GONE
    binding.homeImageViewerPrevImage.setImageDrawable(null)
    binding.homeImageViewerImage.setImageDrawable(null)
    binding.homeImageViewerNextImage.setImageDrawable(null)
    binding.bottomNavGroup.visibility =
      if (currentWebScreen == WebScreen.HOME && binding.loginPage.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    if (resumeCarousel && currentWebScreen == WebScreen.HOME && currentHomeImages.size > 1) {
      mainHandler.removeCallbacks(homeCarouselRunnable)
      mainHandler.postDelayed(homeCarouselRunnable, 4200)
    }
  }

  private fun showHomeImageViewerStep(direction: Int) {
    if (currentHomeImages.isEmpty()) return
    homeViewerInertiaAnimator?.cancel()
    homeViewerInertiaAnimator = null
    val previousIndex = homeViewerActiveIndex
    val nextIndex = (homeViewerActiveIndex + direction + currentHomeImages.size) % currentHomeImages.size
    saveImmediateReturnStateFor(previousIndex, nextIndex, currentHomeViewerState())
    homeViewerActiveIndex = nextIndex
    renderHomeImageViewer(useFullResolution = false, resetTransform = true, sourceIndex = previousIndex)
    preloadHomeViewerAssets(homeViewerActiveIndex)
  }

  private fun renderHomeImageViewer(useFullResolution: Boolean, resetTransform: Boolean, sourceIndex: Int? = null) {
    val asset = currentHomeImages.getOrNull(homeViewerActiveIndex) ?: return
    val currentIndex = homeViewerActiveIndex
    val previous = currentHomeImages.getOrNull((homeViewerActiveIndex - 1 + currentHomeImages.size) % currentHomeImages.size)
    val next = currentHomeImages.getOrNull((homeViewerActiveIndex + 1) % currentHomeImages.size)
    val savedState = immediateReturnStateFor(currentIndex, sourceIndex)
    val previousState = previous?.let { previewStateForIndex((homeViewerActiveIndex - 1 + currentHomeImages.size) % currentHomeImages.size) }
    val nextState = next?.let { previewStateForIndex((homeViewerActiveIndex + 1) % currentHomeImages.size) }
    if (resetTransform) {
      homeViewerScale = savedState?.scale ?: 1f
      homeViewerPanX = savedState?.panX ?: 0f
      homeViewerPanY = savedState?.panY ?: 0f
      homeViewerTrackOffset = 0f
    }
    val shouldUseFull = savedState?.useFullResolution == true || useFullResolution
    val targetAssetPath = if (shouldUseFull) asset.fullAssetPath else asset.detailAssetPath
    val previousAssetPath = when {
      previous == null -> null
      previousState?.useFullResolution == true -> previous.fullAssetPath
      else -> previous.detailAssetPath
    }
    val nextAssetPath = when {
      next == null -> null
      nextState?.useFullResolution == true -> next.fullAssetPath
      else -> next.detailAssetPath
    }
    val stageWidth = binding.homeImageViewerStage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    binding.homeImageViewerPrevImage.translationX = -stageWidth.toFloat()
    binding.homeImageViewerImage.translationX = 0f
    binding.homeImageViewerNextImage.translationX = stageWidth.toFloat()
    binding.homeImageViewerPrevImage.alpha = 0f
    binding.homeImageViewerNextImage.alpha = 0f
    if (homeViewerCurrentAssetPath != targetAssetPath) {
      loadHomeImageInto(binding.homeImageViewerImage, targetAssetPath)
      homeViewerCurrentAssetPath = targetAssetPath
    }
    loadHomeImageInto(binding.homeImageViewerPrevImage, previousAssetPath)
    loadHomeImageInto(binding.homeImageViewerNextImage, nextAssetPath)
    binding.homeImageViewerCaption.text = asset.caption
    val showNav = currentHomeImages.size > 1
    binding.homeImageViewerPrevButton.visibility = if (showNav) View.VISIBLE else View.GONE
    binding.homeImageViewerNextButton.visibility = if (showNav) View.VISIBLE else View.GONE
    if (binding.homeImageViewerStage.width > 0 && binding.homeImageViewerStage.height > 0) {
      applyHomeImageViewerMatrix()
    } else {
      binding.homeImageViewerStage.post { applyHomeImageViewerMatrix() }
    }
  }

  private fun maybeUpgradeHomeImageViewerResolution() {
    val asset = currentHomeImages.getOrNull(homeViewerActiveIndex) ?: return
    if (homeViewerScale <= 1.05f) return
    if (asset.fullAssetPath.isBlank() || homeViewerCurrentAssetPath == asset.fullAssetPath) return
    renderHomeImageViewer(useFullResolution = true, resetTransform = false, sourceIndex = null)
  }

  private fun preloadHomeViewerAssets(index: Int) {
    if (currentHomeImages.isEmpty()) return
    val current = currentHomeImages.getOrNull(index)
    val previous = currentHomeImages.getOrNull((index - 1 + currentHomeImages.size) % currentHomeImages.size)
    val next = currentHomeImages.getOrNull((index + 1) % currentHomeImages.size)
    val assetPaths = listOfNotNull(
      current?.detailAssetPath,
      current?.fullAssetPath,
      previous?.detailAssetPath,
      next?.detailAssetPath
    ).filter { it.isNotBlank() }.distinct()
    if (assetPaths.isEmpty()) return
    ioExecutor.execute {
      assetPaths.forEach { assetPath ->
        loadHomeBitmap(assetPath)
      }
    }
  }

  private fun startHomeImageViewerInertia(velocityX: Float, velocityY: Float) {
    if (!homeViewerVisible || homeViewerScale <= 1f || homeViewerScaleDetector.isInProgress) return
    val speed = kotlin.math.hypot(velocityX.toDouble(), velocityY.toDouble()).toFloat()
    if (speed < 180f) return

    val travelX = (velocityX * 0.11f).coerceIn(-dpToPx(80).toFloat(), dpToPx(80).toFloat())
    val travelY = (velocityY * 0.11f).coerceIn(-dpToPx(80).toFloat(), dpToPx(80).toFloat())
    if (abs(travelX) < 1f && abs(travelY) < 1f) return

    val startX = homeViewerPanX
    val startY = homeViewerPanY
    homeViewerInertiaAnimator?.cancel()
    homeViewerInertiaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 220L
      interpolator = android.view.animation.DecelerateInterpolator()
      addUpdateListener { animator ->
        if (!homeViewerVisible) return@addUpdateListener
        val progress = animator.animatedValue as Float
        homeViewerPanX = startX + (travelX * progress)
        homeViewerPanY = startY + (travelY * progress)
        applyHomeImageViewerMatrix()
      }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          if (homeViewerInertiaAnimator === this@apply) {
            homeViewerInertiaAnimator = null
          }
        }

        override fun onAnimationCancel(animation: android.animation.Animator) {
          if (homeViewerInertiaAnimator === this@apply) {
            homeViewerInertiaAnimator = null
          }
        }
      })
      start()
    }
  }

  private fun shouldStartZoomedHomeViewerSwipe(totalDeltaX: Float, totalDeltaY: Float): Boolean {
    if (abs(totalDeltaX) < dpToPx(8) || abs(totalDeltaX) < abs(totalDeltaY) * 0.8f) {
      return false
    }
    val maxPanX = currentHomeViewerMaxPanX()
    if (maxPanX <= 0f) {
      return true
    }
    val edgeThreshold = dpToPx(10).toFloat()
    val atLeftEdge = homeViewerPanX <= (-maxPanX + edgeThreshold)
    val atRightEdge = homeViewerPanX >= (maxPanX - edgeThreshold)
    return (atLeftEdge && totalDeltaX < 0f) || (atRightEdge && totalDeltaX > 0f)
  }

  private fun currentHomeViewerMaxPanX(): Float {
    val drawable = binding.homeImageViewerImage.drawable ?: return 0f
    val viewportWidth = binding.homeImageViewerStage.width.toFloat()
    val viewportHeight = binding.homeImageViewerStage.height.toFloat()
    if (viewportWidth <= 0f || viewportHeight <= 0f) return 0f
    val imageWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return 0f
    val imageHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return 0f
    val baseScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val displayedWidth = imageWidth * baseScale * homeViewerScale
    return max(0f, (displayedWidth - viewportWidth) / 2f)
  }

  private fun currentHomeViewerMaxPanY(): Float {
    val drawable = binding.homeImageViewerImage.drawable ?: return 0f
    val viewportWidth = binding.homeImageViewerStage.width.toFloat()
    val viewportHeight = binding.homeImageViewerStage.height.toFloat()
    if (viewportWidth <= 0f || viewportHeight <= 0f) return 0f
    val imageWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return 0f
    val imageHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return 0f
    val baseScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val displayedHeight = imageHeight * baseScale * homeViewerScale
    return max(0f, (displayedHeight - viewportHeight) / 2f)
  }

  private fun clampHomeViewerPanX(value: Float): Float {
    val maxPanX = currentHomeViewerMaxPanX()
    return value.coerceIn(-maxPanX, maxPanX)
  }

  private fun clampHomeViewerPanY(value: Float): Float {
    val maxPanY = currentHomeViewerMaxPanY()
    return value.coerceIn(-maxPanY, maxPanY)
  }

  private fun currentHomeViewerState(): HomeViewerTransformState {
    return HomeViewerTransformState(
      scale = homeViewerScale,
      panX = homeViewerPanX,
      panY = homeViewerPanY,
      useFullResolution = homeViewerCurrentAssetPath == currentHomeImages.getOrNull(homeViewerActiveIndex)?.fullAssetPath
    )
  }

  private fun saveImmediateReturnStateFor(targetIndex: Int, sourceIndex: Int, state: HomeViewerTransformState) {
    val reverseKey = "$sourceIndex:$targetIndex"
    if (!homeViewerTransitionStates.containsKey(reverseKey)) {
      homeViewerTransitionStates.clear()
    }
    homeViewerTransitionStates["$targetIndex:$sourceIndex"] = state
  }

  private fun immediateReturnStateFor(index: Int, sourceIndex: Int?): HomeViewerTransformState? {
    if (sourceIndex == null) return null
    return homeViewerTransitionStates["$index:$sourceIndex"]
  }

  private fun previewStateForIndex(index: Int): HomeViewerTransformState? {
    return homeViewerTransitionStates["$index:$homeViewerActiveIndex"]
  }

  private fun applyHomeImageViewerTrackOffset(offset: Float) {
    val width = (binding.homeImageViewerStage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    homeViewerTrackOffset = offset.coerceIn(-width, width)
    applyHomeImageViewerMatrix()
  }

  private fun finishHomeImageViewerDrag(deltaX: Float, velocityX: Float) {
    val width = (binding.homeImageViewerStage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    val threshold = width * 0.22f
    val velocityThreshold = 850f
    when {
      deltaX <= -threshold || velocityX <= -velocityThreshold ->
        animateHomeImageViewerOffsetTo(-width, (homeViewerActiveIndex + 1) % currentHomeImages.size)
      deltaX >= threshold || velocityX >= velocityThreshold ->
        animateHomeImageViewerOffsetTo(width, (homeViewerActiveIndex - 1 + currentHomeImages.size) % currentHomeImages.size)
      else ->
        animateHomeImageViewerOffsetTo(0f, null)
    }
  }

  private fun animateHomeImageViewerOffsetTo(targetOffset: Float, targetIndex: Int?) {
    homeViewerInertiaAnimator?.cancel()
    homeViewerInertiaAnimator = null
    val from = homeViewerTrackOffset
    val sourceIndex = homeViewerActiveIndex
    if (from == targetOffset) {
      if (targetIndex != null) {
        saveImmediateReturnStateFor(sourceIndex, targetIndex, currentHomeViewerState())
        homeViewerActiveIndex = targetIndex
        renderHomeImageViewer(useFullResolution = false, resetTransform = true, sourceIndex = sourceIndex)
      } else {
        applyHomeImageViewerTrackOffset(0f)
      }
      return
    }
    ValueAnimator.ofFloat(from, targetOffset).apply {
      duration = 220L
      interpolator = android.view.animation.DecelerateInterpolator()
      addUpdateListener { animator ->
        applyHomeImageViewerTrackOffset(animator.animatedValue as Float)
      }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          if (targetIndex != null) {
            saveImmediateReturnStateFor(sourceIndex, targetIndex, currentHomeViewerState())
            homeViewerActiveIndex = targetIndex
            renderHomeImageViewer(useFullResolution = false, resetTransform = true, sourceIndex = sourceIndex)
          } else {
            applyHomeImageViewerTrackOffset(0f)
          }
        }
      })
      start()
    }
  }

  private fun applyHomeImageViewerMatrix() {
    val viewportWidth = binding.homeImageViewerStage.width.toFloat()
    val viewportHeight = binding.homeImageViewerStage.height.toFloat()
    if (viewportWidth <= 0f || viewportHeight <= 0f) return
    val width = viewportWidth
    val prevIndex = (homeViewerActiveIndex - 1 + currentHomeImages.size) % currentHomeImages.size
    val nextIndex = (homeViewerActiveIndex + 1) % currentHomeImages.size
    val prevState = previewStateForIndex(prevIndex)
    val nextState = previewStateForIndex(nextIndex)
    binding.homeImageViewerPrevImage.translationX = -width + homeViewerTrackOffset
    binding.homeImageViewerImage.translationX = homeViewerTrackOffset
    binding.homeImageViewerNextImage.translationX = width + homeViewerTrackOffset
    val showAdjacent = abs(homeViewerTrackOffset) > 0.5f
    binding.homeImageViewerPrevImage.alpha = if (showAdjacent) 1f else 0f
    binding.homeImageViewerNextImage.alpha = if (showAdjacent) 1f else 0f
    applyHomeImageViewerImageMatrix(
      binding.homeImageViewerPrevImage,
      prevState?.scale ?: 1f,
      prevState?.panX ?: 0f,
      prevState?.panY ?: 0f
    )
    applyHomeImageViewerImageMatrix(
      binding.homeImageViewerNextImage,
      nextState?.scale ?: 1f,
      nextState?.panX ?: 0f,
      nextState?.panY ?: 0f
    )
    applyHomeImageViewerImageMatrix(binding.homeImageViewerImage, homeViewerScale, homeViewerPanX, homeViewerPanY)
  }

  private fun applyHomeImageViewerImageMatrix(view: ImageView, scale: Float, panX: Float, panY: Float) {
    val drawable = view.drawable ?: return
    val viewportWidth = binding.homeImageViewerStage.width.toFloat()
    val viewportHeight = binding.homeImageViewerStage.height.toFloat()
    val imageWidth = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: return
    val imageHeight = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: return
    val baseScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val totalScale = baseScale * scale
    val displayedWidth = imageWidth * totalScale
    val displayedHeight = imageHeight * totalScale
    val maxPanX = max(0f, (displayedWidth - viewportWidth) / 2f)
    val maxPanY = max(0f, (displayedHeight - viewportHeight) / 2f)
    val safePanX = panX.coerceIn(-maxPanX, maxPanX)
    val safePanY = panY.coerceIn(-maxPanY, maxPanY)
    val translateX = ((viewportWidth - displayedWidth) / 2f) + safePanX
    val translateY = ((viewportHeight - displayedHeight) / 2f) + safePanY
    val matrix = Matrix()
    matrix.postScale(totalScale, totalScale)
    matrix.postTranslate(translateX, translateY)
    view.imageMatrix = matrix
    if (view === binding.homeImageViewerImage) {
      homeViewerPanX = safePanX
      homeViewerPanY = safePanY
    }
  }

  private fun showPreviousHomeImage(animated: Boolean) {
    if (currentHomeImages.size <= 1) return
    showHomeImageWithAnimation((currentHomeImageIndex - 1 + currentHomeImages.size) % currentHomeImages.size, forward = false, animated = animated)
  }

  private fun showNextHomeImage(animated: Boolean) {
    if (currentHomeImages.size <= 1) return
    showHomeImageWithAnimation((currentHomeImageIndex + 1) % currentHomeImages.size, forward = true, animated = animated)
  }

  private fun showHomeImageWithAnimation(targetIndex: Int, forward: Boolean, animated: Boolean) {
    if (!animated || targetIndex == currentHomeImageIndex) {
      setHomeImageIndex(targetIndex)
      return
    }

    val width = (binding.homeImageViewport.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    animateHomeImageOffsetTo(if (forward) -width else width, targetIndex)
  }

  private fun applyHomeImageTrackOffset(offset: Float) {
    val width = (binding.homeImageViewport.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    val clamped = offset.coerceIn(-width, width)
    homeImageTrackOffset = clamped
    binding.homeImagePrevView.translationX = -width + clamped
    binding.homeImageCurrentView.translationX = clamped
    binding.homeImageNextView.translationX = width + clamped
  }

  private fun finishHomeImageDrag(deltaX: Float, velocityX: Float) {
    val width = (binding.homeImageViewport.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    val threshold = width * 0.22f
    val velocityThreshold = 850f
    when {
      deltaX <= -threshold || velocityX <= -velocityThreshold ->
        animateHomeImageOffsetTo(-width, (currentHomeImageIndex + 1) % currentHomeImages.size)
      deltaX >= threshold || velocityX >= velocityThreshold ->
        animateHomeImageOffsetTo(width, (currentHomeImageIndex - 1 + currentHomeImages.size) % currentHomeImages.size)
      else ->
        animateHomeImageOffsetTo(0f, null)
    }
  }

  private fun animateHomeImageOffsetTo(targetOffset: Float, targetIndex: Int?) {
    homeImageAnimator?.cancel()
    homeImageAnimator = null
    val from = homeImageTrackOffset
    if (from == targetOffset) {
      if (targetIndex != null) {
        setHomeImageIndex(targetIndex)
      } else {
        applyHomeImageTrackOffset(0f)
      }
      return
    }
    val animator = ValueAnimator.ofFloat(from, targetOffset)
    animator.duration = 220
    animator.addUpdateListener { valueAnimator ->
      applyHomeImageTrackOffset(valueAnimator.animatedValue as Float)
    }
    homeImageAnimator = AnimatorSet().apply {
      play(animator)
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          homeImageAnimator = null
          if (targetIndex != null) {
            setHomeImageIndex(targetIndex)
          } else {
            applyHomeImageTrackOffset(0f)
          }
        }

        override fun onAnimationCancel(animation: android.animation.Animator) {
          homeImageAnimator = null
        }
      })
      start()
    }
  }

  private fun loadHomeImageInto(view: ImageView, assetPath: String?) {
    if (assetPath.isNullOrBlank()) {
      view.setImageDrawable(null)
      return
    }
    view.setImageBitmap(loadHomeBitmap(assetPath))
  }

  @Synchronized
  private fun loadHomeBitmap(assetPath: String): Bitmap? {
    homeBitmapCache.get(assetPath)?.let { return it }
    val bitmap = assets.open(assetPath).use { BitmapFactory.decodeStream(it) } ?: return null
    homeBitmapCache.put(assetPath, bitmap)
    return bitmap
  }

  private fun renderHomeDots(activeIndex: Int) {
    binding.homeDots.removeAllViews()
    currentHomeImages.forEachIndexed { index, _ ->
      val dot = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(12), dpToPx(12)).also { params ->
          params.marginStart = dpToPx(6)
          params.marginEnd = dpToPx(6)
        }
        background = android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.OVAL
          setColor(Color.parseColor(if (index == activeIndex) "#69ACEC" else "#D9DEE7"))
        }
        alpha = 0.94f
      }
      binding.homeDots.addView(dot)
    }
  }

  private fun renderHomeMenu() {
    binding.homeMenuGrid.removeAllViews()
    HOME_MENU_ITEMS.forEach { item ->
      val itemView = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setPadding(dpToPx(2), 0, dpToPx(2), 0)
        layoutParams = GridLayout.LayoutParams().apply {
          width = 0
          columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
          setMargins(0, 0, 0, dpToPx(8))
        }
      }

      val icon = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(50))
        setImageResource(item.iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#9CA6B5"))
        alpha = if (item.enabled) 1f else 0.92f
      }

      val label = TextView(this).apply {
        text = item.label
        textSize = 11f
        setTextColor(Color.parseColor("#77818F"))
        gravity = android.view.Gravity.CENTER
        minLines = 2
      }

      itemView.addView(icon)
      itemView.addView(label)
      itemView.setOnClickListener {
        if (item.key == "schedule") {
          showCachedTimetable()
        } else {
          Toast.makeText(this, "该功能入口已预留，暂未实现", Toast.LENGTH_SHORT).show()
        }
      }
      binding.homeMenuGrid.addView(itemView)
    }
  }

  private fun loadCoursesFromCacheJson(): List<TimetableCourse> {
    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)
    if (!cacheJsonFile.exists() || cacheJsonFile.length() <= 2L) return emptyList()
    val rawJson = runCatching { cacheJsonFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
    return runCatching {
      val array = JSONArray(rawJson)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.optJSONObject(index) ?: continue
          add(
            TimetableCourse(
              courseName = item.optString("courseName"),
              weekday = item.optString("weekday"),
              periods = item.optString("periods"),
              classroom = item.optString("classroom"),
              weeks = item.optString("weeks"),
              teacher = item.optString("teacher"),
              courseCode = item.optString("courseCode"),
              courseSequence = item.optString("courseSequence"),
              courseType = item.optString("courseType"),
              rawText = item.optString("rawText")
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun buildRecentCourses(courses: List<TimetableCourse>): List<HomeRecentEntry> {
    if (courses.isEmpty()) return emptyList()
    val normalized = courses.map { course ->
      val match = Regex("""(\d+)(?:-(\d+))?""").find(course.periods)
      NormalizedCourse(
        course = course,
        startPeriod = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        endPeriod = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        weeks = parseWeeks(course.weeks)
      )
    }
    val today = LocalDate.now()
    val now = LocalTime.now()
    val result = mutableListOf<HomeRecentEntry>()

    for (offset in 0..1) {
      val date = today.plusDays(offset.toLong())
      val weekday = HOME_WEEKDAYS[(date.dayOfWeek.value - 1) % HOME_WEEKDAYS.size]
      val week = HOME_ANCHOR_WEEK + (ChronoUnit.DAYS.between(HOME_ANCHOR_MONDAY, date) / 7).toInt()

      normalized
        .filter { it.course.weekday == weekday && (it.weeks.isEmpty() || it.weeks.contains(week)) }
        .sortedWith(compareBy<NormalizedCourse> { it.startPeriod }.thenBy { it.endPeriod })
        .filter { course ->
          if (offset != 0) return@filter true
          val endText = PERIOD_SLOTS[course.endPeriod]?.second ?: "23:59"
          parseTime(endText) >= now
        }
        .forEach { course ->
          val start = parseTime(PERIOD_SLOTS[course.startPeriod]?.first ?: "00:00")
          val end = parseTime(PERIOD_SLOTS[course.endPeriod]?.second ?: "23:59")
          val isAlert = offset == 0 && ((now >= start && now <= end) || (now < start && ChronoUnit.MINUTES.between(now, start) in 0..15))
          result += HomeRecentEntry(
            displayDay = if (offset == 0) "今日" else "明日",
            title = course.course.courseName,
            meta = "第${course.startPeriod}大节 ${PERIOD_SLOTS[course.startPeriod]?.first.orEmpty()}-${PERIOD_SLOTS[course.endPeriod]?.second.orEmpty()}",
            room = course.course.classroom.ifBlank { "待定" },
            isToday = offset == 0,
            isAlert = isAlert
          )
        }
    }

    return result
  }

  private fun parseWeeks(weeksText: String): List<Int> {
    val matches = Regex("""\d+(?:-\d+)?""").findAll(weeksText)
    return matches.flatMap { match ->
      val token = match.value
      if ("-" in token) {
        val (startText, endText) = token.split("-")
        (startText.toInt()..endText.toInt()).asSequence()
      } else {
        sequenceOf(token.toInt())
      }
    }.toList()
  }

  private fun parseTime(value: String): LocalTime = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.MIN }

  private fun renderRecentCourses(items: List<HomeRecentEntry>) {
    binding.homeRecentList.removeAllViews()
    items.forEachIndexed { index, item ->
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dpToPx(12), 0, dpToPx(12))
      }

      val badge = TextView(this).apply {
        text = item.displayDay
        gravity = android.view.Gravity.CENTER
        textSize = 15f
        layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(52))
        setTextColor(
          Color.parseColor(
            when {
              item.isAlert -> "#B86F69"
              item.isToday -> "#A88539"
              else -> "#7F8979"
            }
          )
        )
        background = android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.OVAL
          setColor(
            Color.parseColor(
              when {
                item.isAlert -> "#F8D8D4"
                item.isToday -> "#F7E39F"
                else -> "#E0F1DC"
              }
            )
          )
        }
      }

      val middle = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
          it.marginStart = dpToPx(10)
          it.marginEnd = dpToPx(10)
        }
      }

      val title = TextView(this).apply {
        text = item.title
        textSize = 16f
        setTextColor(Color.parseColor("#59626F"))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
      val meta = TextView(this).apply {
        text = item.meta
        textSize = 11f
        setTextColor(Color.parseColor("#B6BCC5"))
      }
      middle.addView(title)
      middle.addView(meta)

      val room = TextView(this).apply {
        text = item.room
        textSize = 16f
        setTextColor(Color.parseColor("#8F959C"))
      }

      row.addView(badge)
      row.addView(middle)
      row.addView(room)
      binding.homeRecentList.addView(row)
      if (index < items.lastIndex) {
        binding.homeRecentList.addView(View(this).apply {
          layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(1)
          )
          setBackgroundColor(Color.parseColor("#33B0BCCB"))
        })
      }
    }
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
