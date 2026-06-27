package com.classsche.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.Manifest
import android.net.Uri
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.classsche.mobile.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
  private var syncingCourseNotificationSwitch = false
  private var syncingExamNotificationSwitch = false
  private var baseToolbarPaddingLeft = 0
  private var baseToolbarPaddingTop = 0
  private var baseToolbarPaddingRight = 0
  private var baseToolbarPaddingBottom = 0
  private var lastStatusBarInsetTop = 0

  private var loginSubmitted = false
  private var isAutoUpdating = false
  private var autoUpdateFailedAttempts = 0
  private var cacheCaptureInProgress = false
  private var showingLiveTimetable = false
  private var currentWebScreen = WebScreen.HOME
  private var homePageLoaded = false
  private var currentAssetExportId: String? = null
  private var renderedHomeSignature: String? = null
  private var loginSessionBootstrapped = false
  private var pendingNotificationToggleTarget: NotificationToggleTarget? = null
  private var updateCheckInProgress = false
  private var pendingApkInstallFile: File? = null
  private var updateDownloadDialog: AlertDialog? = null
  private var updateDownloadTitleView: TextView? = null
  private var updateDownloadProgressBar: ProgressBar? = null
  private var updateDownloadProgressText: TextView? = null
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
  private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      attemptEnableNotificationAfterPermission()
    } else {
      when (pendingNotificationToggleTarget) {
        NotificationToggleTarget.COURSE -> {
          CourseNotificationService.saveEnabled(this, false)
          setCourseNotificationSwitchChecked(false)
          Toast.makeText(this, "未授予通知权限，无法开启上课通知", Toast.LENGTH_SHORT).show()
        }
        NotificationToggleTarget.EXAM -> {
          ExamOngoingNotificationScheduler.saveEnabled(this, false)
          setExamNotificationSwitchChecked(false)
          Toast.makeText(this, "未授予通知权限，无法开启考试通知", Toast.LENGTH_SHORT).show()
        }
        null -> Unit
      }
    }
    pendingNotificationToggleTarget = null
    refreshNotificationInputEnabledState()
  }
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

  private enum class NotificationToggleTarget {
    COURSE,
    EXAM
  }

  private data class HomeRecentEntry(
    val displayDay: String,
    val title: String,
    val meta: String,
    val room: String,
    val isToday: Boolean,
    val isAlert: Boolean
  )

  private data class HomeRecentExamEntry(
    val displayDay: String,
    val title: String,
    val meta: String,
    val roomSeat: String,
    val isToday: Boolean,
    val isAlert: Boolean
  )

  private data class NormalizedCourse(
    val course: TimetableCourse,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>
  )

  private data class ParsedExamTime(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime
  )

  private data class NormalizedExam(
    val exam: ExamArrangement,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime
  )

  private data class AppReleaseInfo(
    val sourceLabel: String,
    val pageUrl: String,
    val versionName: String,
    val apkUrl: String?
  )

  private enum class WebScreen {
    HOME,
    PROFILE,
    LOGIN,
    TIMETABLE,
    EXAM,
    SCORE
  }

  companion object {
    private const val LOGIN_URL = "http://202.119.81.113:8080"
    private const val TIMETABLE_URL = "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do"
    private const val HOME_ASSET_BASE_URL = "file:///android_asset/"
    private const val GENERATED_HOME_HTML_FILE = "home-view-generated.html"
    private const val GENERATED_CACHE_HTML_FILE = "timetable-view-generated.html"
    private const val CACHE_JSON_FILE = "timetable.json"
    private const val EXAM_JSON_FILE = "exam-list.json"
    private const val SCORE_JSON_FILE = "score-list.json"
    private const val CACHE_RAW_HTML_FILE = "timetable.raw.html"
    private const val EXAM_QUERY_URL = "http://202.119.81.112:9080/njlgdx/xsks/xsksap_query"
    private const val EXAM_LIST_URL = "http://202.119.81.112:9080/njlgdx/xsks/xsksap_list"
    private const val SCORE_LIST_URL = "http://202.119.81.112:9080/njlgdx/kscj/cjcx_list"
    private const val GITEE_HOME_URL = "https://gitee.com/flyshaoyu/njust_localclasssche"
    private const val GITHUB_HOME_URL = "https://github.com/flyShaoyu/NJUSTLocalClassSche"
    private const val GITEE_RELEASES_URL = "https://gitee.com/flyshaoyu/njust_localclasssche/releases"
    private const val GITHUB_RELEASES_URL = "https://github.com/flyShaoyu/NJUSTLocalClassSche/releases"
    private const val UPDATE_USER_AGENT = "Mozilla/5.0 ClassScheMobile"
    private const val EXAM_DEFAULT_SEMESTER = "2025-2026-2"
    private const val PREF_USERNAME = "username"
    private const val PREF_PASSWORD = "password"
    private const val PREF_ASSET_EXPORT_ID = "asset_export_id"
    private const val CACHE_META_ASSET = "cache-meta.json"
    private val HOME_MENU_ITEMS = listOf(
      HomeMenuEntry("exam", "考试安排", R.drawable.ic_home_exam, true),
      HomeMenuEntry("score", "成绩查询", R.drawable.ic_home_score, true),
      HomeMenuEntry("level", "等级考试", R.drawable.ic_home_level, false),
      HomeMenuEntry("add", "添加课表", R.drawable.ic_home_add, false),
      HomeMenuEntry("schedule", "课表查询", R.drawable.ic_home_schedule, true),
      HomeMenuEntry("room", "空闲教室", R.drawable.ic_home_room, false),
      HomeMenuEntry("site", "常用网站", R.drawable.ic_home_site, false),
      HomeMenuEntry("refresh", "更新课表", R.drawable.ic_home_refresh, true),
      HomeMenuEntry("library", "图书搜索", R.drawable.ic_home_library, false),
      HomeMenuEntry("borrow", "借阅信息", R.drawable.ic_home_borrow, false)
    )
    private val HOME_WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    private val HOME_WEEK_TITLES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val HOME_ANCHOR_WEEK = 6
    private val HOME_ANCHOR_MONDAY: LocalDate = LocalDate.of(2026, 4, 6)
    private val EXAM_TIME_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})~(\d{2}:\d{2})""")
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

    private fun serializeForScript(json: String): String {
      return json
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    }

    private fun normalizeText(value: String): String =
      value
        .replace('\u00A0', ' ')
        .replace("&nbsp;", " ")
        .replace("\r", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun cleanInlineText(value: String): String =
      normalizeText(value).replace(Regex("\\s*\\n\\s*"), " ")
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
    setupNotificationSettings()
    restoreNotificationSettings()
    CourseNotificationScheduler.sync(this)
    ExamOngoingNotificationScheduler.sync(this)

    showHomePage()
    binding.root.post {
      refreshGeneratedCacheAfterStartup()
    }
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

  override fun onResume() {
    super.onResume()
    restoreNotificationSettings()
    updateNotificationLeadTimeSummary()
    refreshNotificationInputEnabledState()
    updateAppVersionSummary()
    resumePendingApkInstallIfReady()
  }

  override fun onBackPressed() {
    if (homeViewerVisible) {
      closeHomeImageViewer()
      return
    }

    if (binding.loginPage.visibility == View.VISIBLE) {
      showProfilePage()
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
      when (currentWebScreen) {
        WebScreen.TIMETABLE, WebScreen.EXAM, WebScreen.SCORE -> showHomePage()
        WebScreen.LOGIN -> showProfilePage()
        else -> Unit
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
          url.contains("exam-view", ignoreCase = true) -> {
            applyWebScreen(WebScreen.EXAM)
          }
          url.contains("score-view", ignoreCase = true) -> {
            applyWebScreen(WebScreen.SCORE)
          }
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
    binding.navProfileButton.setOnClickListener { showProfilePage() }
    binding.profileAccountCard.setOnClickListener { showLoginPage() }
    binding.profileOpenNotificationRow.setOnClickListener {
      startActivity(Intent(this, NotificationSettingsActivity::class.java))
    }
    binding.profileCheckUpdateRow.setOnClickListener {
      checkForAppUpdate()
    }
    binding.profileOpenLogsRow.setOnClickListener {
      startActivity(Intent(this, LogViewerActivity::class.java))
    }
    binding.profileSoftwareGiteeRow.setOnClickListener {
      openUrl(GITEE_HOME_URL)
    }
    binding.profileSoftwareGithubRow.setOnClickListener {
      openUrl(GITHUB_HOME_URL)
    }

    binding.openLoginButton.setOnClickListener {
      isAutoUpdating = false
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

  private fun setupNotificationSettings() {
    binding.courseNotificationSwitch.setOnCheckedChangeListener { _, isChecked ->
      if (syncingCourseNotificationSwitch) return@setOnCheckedChangeListener
      onNotificationToggleRequested(NotificationToggleTarget.COURSE, isChecked)
    }
    binding.examNotificationSwitch.setOnCheckedChangeListener { _, isChecked ->
      if (syncingExamNotificationSwitch) return@setOnCheckedChangeListener
      onNotificationToggleRequested(NotificationToggleTarget.EXAM, isChecked)
    }
  }

  private fun restoreNotificationSettings() {
    setCourseNotificationSwitchChecked(CourseNotificationService.isEnabled(this))
    setExamNotificationSwitchChecked(ExamOngoingNotificationScheduler.isEnabled(this))
    updateNotificationLeadTimeSummary()
    refreshNotificationInputEnabledState()
  }

  private fun onNotificationToggleRequested(target: NotificationToggleTarget, enabled: Boolean) {
    if (enabled) {
      pendingNotificationToggleTarget = target
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          return
        }
      }
      attemptEnableNotificationAfterPermission()
      return
    }
    pendingNotificationToggleTarget = null
    when (target) {
      NotificationToggleTarget.COURSE -> {
        CourseNotificationService.saveEnabled(this, false)
        CourseNotificationScheduler.cancelAll(this)
      }
      NotificationToggleTarget.EXAM -> {
        ExamOngoingNotificationScheduler.saveEnabled(this, false)
        ExamOngoingNotificationScheduler.cancelAll(this)
      }
    }
    refreshNotificationInputEnabledState()
  }

  private fun attemptEnableNotificationAfterPermission() {
    val target = pendingNotificationToggleTarget ?: return
    if (!CourseNotificationScheduler.canScheduleExactAlarms(this)) {
      when (target) {
        NotificationToggleTarget.COURSE -> {
          CourseNotificationService.saveEnabled(this, false)
          setCourseNotificationSwitchChecked(false)
        }
        NotificationToggleTarget.EXAM -> {
          ExamOngoingNotificationScheduler.saveEnabled(this, false)
          setExamNotificationSwitchChecked(false)
        }
      }
      Toast.makeText(this, "请先开启系统的精确闹钟权限，才能保证上课和考试提醒准时触发", Toast.LENGTH_LONG).show()
      startActivity(Intent(this, NotificationSettingsActivity::class.java))
      pendingNotificationToggleTarget = null
      refreshNotificationInputEnabledState()
      return
    }
    when (target) {
      NotificationToggleTarget.COURSE -> {
        CourseNotificationService.saveEnabled(this, true)
        setCourseNotificationSwitchChecked(true)
        CourseNotificationScheduler.sync(this)
      }
      NotificationToggleTarget.EXAM -> {
        ExamOngoingNotificationScheduler.saveEnabled(this, true)
        setExamNotificationSwitchChecked(true)
        ExamOngoingNotificationScheduler.sync(this)
      }
    }
    pendingNotificationToggleTarget = null
    refreshNotificationInputEnabledState()
  }

  private fun setCourseNotificationSwitchChecked(checked: Boolean) {
    syncingCourseNotificationSwitch = true
    binding.courseNotificationSwitch.isChecked = checked
    syncingCourseNotificationSwitch = false
  }

  private fun setExamNotificationSwitchChecked(checked: Boolean) {
    syncingExamNotificationSwitch = true
    binding.examNotificationSwitch.isChecked = checked
    syncingExamNotificationSwitch = false
  }

  private fun refreshNotificationInputEnabledState() {
    val enabled = binding.courseNotificationSwitch.isChecked || binding.examNotificationSwitch.isChecked
    binding.profileOpenNotificationRow.isEnabled = enabled
    binding.profileOpenNotificationRow.alpha = if (enabled) 1f else 0.45f
    binding.notificationSettingSummary.alpha = if (enabled) 1f else 0.45f
  }

  private fun updateNotificationLeadTimeSummary() {
    val courseSummary = getString(
      R.string.notification_setting_summary_format,
      CourseNotificationService.getLeadHours(this),
      CourseNotificationService.getLeadMinutePart(this)
    )
    val examSummary = getString(
      R.string.notification_exam_setting_summary_format,
      ExamOngoingNotificationScheduler.formatLeadLabel(ExamOngoingNotificationScheduler.getLeadMinutes(this))
    )
    binding.notificationSettingSummary.text = getString(
      R.string.notification_profile_summary_format,
      courseSummary,
      examSummary
    )
  }

  private fun updateAppVersionSummary(statusText: String? = null) {
    val currentVersion = currentAppVersionName()
    binding.profileCheckUpdateSummary.text = when {
      statusText.isNullOrBlank() -> getString(R.string.profile_current_version_format, currentVersion)
      else -> statusText
    }
  }

  private fun checkForAppUpdate() {
    if (updateCheckInProgress) {
      appendDebugLog("UPDATE", "INFO", "重复触发检查更新，已忽略")
      Toast.makeText(this, "正在检查更新，请稍候", Toast.LENGTH_SHORT).show()
      return
    }

    val currentVersion = currentAppVersionName()
    appendDebugLog("UPDATE", "START", "开始检查更新，当前版本=$currentVersion")
    updateCheckInProgress = true
    updateAppVersionSummary(getString(R.string.profile_update_checking, currentVersion))
    updateStatus("正在检查更新…")

    ioExecutor.execute {
      var giteeError: Throwable? = null
      val release = try {
        fetchLatestReleaseInfo("Gitee", GITEE_RELEASES_URL)
      } catch (error: Throwable) {
        giteeError = error
        appendDebugLog("UPDATE", "WARN", "Gitee 检查失败，准备切换 GitHub：${error.message ?: "unknown"}")
        try {
          fetchLatestReleaseInfo("GitHub", GITHUB_RELEASES_URL)
        } catch (fallbackError: Throwable) {
          mainHandler.post {
            updateCheckInProgress = false
            updateAppVersionSummary(getString(R.string.profile_update_failed_format, currentVersion))
            showUpdateCheckFailedDialog(giteeError, fallbackError)
          }
          return@execute
        }
      }

      mainHandler.post {
        updateCheckInProgress = false
        val latestVersion = release.versionName
        val comparison = compareVersionNames(latestVersion, currentVersion)
        if (comparison > 0) {
          appendDebugLog("UPDATE", "SUCCESS", "发现新版本 $latestVersion，来源=${release.sourceLabel}")
          updateAppVersionSummary(getString(R.string.profile_update_available_format, currentVersion, latestVersion))
          showUpdateAvailableDialog(currentVersion, release)
        } else {
          appendDebugLog("UPDATE", "SUCCESS", "当前已是最新版本，远端版本=$latestVersion")
          updateAppVersionSummary(getString(R.string.profile_update_latest_format, currentVersion))
          AlertDialog.Builder(this)
            .setTitle("已是最新版本")
            .setMessage("当前版本 $currentVersion 已是最新版本。")
            .setPositiveButton("知道了", null)
            .show()
        }
      }
    }
  }

  private fun showUpdateCheckFailedDialog(giteeError: Throwable?, githubError: Throwable?) {
    val giteeMessage = giteeError?.message?.takeIf { it.isNotBlank() } ?: "未知错误"
    val githubMessage = githubError?.message?.takeIf { it.isNotBlank() } ?: "未知错误"
    appendDebugLog("UPDATE", "FAIL", "检查更新失败：Gitee=$giteeMessage；GitHub=$githubMessage")
    AlertDialog.Builder(this)
      .setTitle("检查更新失败")
      .setMessage("Gitee 检查失败：$giteeMessage\nGitHub 检查失败：$githubMessage")
      .setNegativeButton("取消", null)
      .setNeutralButton("打开 GitHub") { _, _ -> openUrl(GITHUB_RELEASES_URL) }
      .setPositiveButton("打开 Gitee") { _, _ -> openUrl(GITEE_RELEASES_URL) }
      .show()
  }

  private fun showUpdateAvailableDialog(currentVersion: String, release: AppReleaseInfo) {
    val message = buildString {
      append("当前版本：").append(currentVersion).append('\n')
      append("最新版本：").append(release.versionName).append('\n')
      append("来源：").append(release.sourceLabel)
      if (release.apkUrl.isNullOrBlank()) {
        append("\n\n未在发布页中找到 APK 下载链接，可打开发布页手动下载安装。")
      }
    }

    AlertDialog.Builder(this)
      .setTitle("发现新版本")
      .setMessage(message)
      .setNegativeButton("取消", null)
      .setNeutralButton("查看发布页") { _, _ -> openUrl(release.pageUrl) }
      .setPositiveButton(if (release.apkUrl.isNullOrBlank()) "知道了" else "下载安装") { _, _ ->
        if (release.apkUrl.isNullOrBlank()) {
          openUrl(release.pageUrl)
        } else {
          downloadAndInstallReleaseApk(release)
        }
      }
      .show()
  }

  private fun downloadAndInstallReleaseApk(release: AppReleaseInfo) {
    val apkUrl = release.apkUrl
    if (apkUrl.isNullOrBlank()) {
      appendDebugLog("UPDATE_DOWNLOAD", "WARN", "当前来源 ${release.sourceLabel} 未提供 APK 链接，直接打开 Gitee 页面")
      openUrl(giteeReleasePageUrlForVersion(release.versionName, release))
      return
    }

    appendDebugLog("UPDATE_DOWNLOAD", "START", "开始下载版本 ${release.versionName}，首选来源=${release.sourceLabel}")
    updateStatus("正在下载 ${release.versionName} 安装包…")
    Toast.makeText(this, "开始下载 ${release.versionName} 安装包", Toast.LENGTH_SHORT).show()
    showUpdateDownloadDialog(release.versionName)

    ioExecutor.execute {
      val targetDir = File(cacheDir, "updates").apply { mkdirs() }
      val targetFile = File(targetDir, "classsche-${release.versionName}.apk")
      val downloadCandidates = buildReleaseDownloadCandidates(release)
      val failureMessages = mutableListOf<String>()

      for (candidate in downloadCandidates) {
        val candidateApkUrl = candidate.apkUrl
        if (candidateApkUrl.isNullOrBlank()) {
          failureMessages += "${candidate.sourceLabel}：未找到 APK 下载链接"
          continue
        }

        try {
          mainHandler.post {
            updateUpdateDownloadProgress(
              versionName = release.versionName,
              sourceLabel = candidate.sourceLabel,
              downloadedBytes = 0L,
              totalBytes = -1L
            )
          }
          appendDebugLog("UPDATE_DOWNLOAD", "INFO", "尝试从 ${candidate.sourceLabel} 下载 ${release.versionName}")
          downloadFile(candidateApkUrl, targetFile) { downloadedBytes, totalBytes ->
            mainHandler.post {
              updateUpdateDownloadProgress(
                versionName = release.versionName,
                sourceLabel = candidate.sourceLabel,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes
              )
            }
          }
          mainHandler.post {
            dismissUpdateDownloadDialog()
            updateStatus("安装包下载完成，准备安装…")
            promptInstallDownloadedApk(targetFile)
          }
          appendDebugLog("UPDATE_DOWNLOAD", "SUCCESS", "已从 ${candidate.sourceLabel} 下载完成 ${targetFile.length()} bytes")
          return@execute
        } catch (error: Throwable) {
          if (targetFile.exists()) {
            targetFile.delete()
          }
          val message = error.message?.takeIf { it.isNotBlank() } ?: "unknown"
          appendDebugLog("UPDATE_DOWNLOAD", "WARN", "${candidate.sourceLabel} 下载失败：$message")
          failureMessages += "${candidate.sourceLabel}：$message"
        }
      }

      val giteePageUrl = giteeReleasePageUrlForVersion(release.versionName, release)
      mainHandler.post {
        dismissUpdateDownloadDialog()
        updateStatus("安装包下载失败，已打开 Gitee 发布页")
        Toast.makeText(this, "两边安装包都下载失败，已打开 Gitee 发布页", Toast.LENGTH_LONG).show()
        if (failureMessages.isNotEmpty()) {
          AlertDialog.Builder(this)
            .setTitle("下载失败")
            .setMessage(
              buildString {
                append("Gitee 和 GitHub 安装包都下载失败，已为你打开 Gitee 发布页。\n\n")
                append(failureMessages.joinToString("\n"))
              }
            )
            .setPositiveButton("知道了", null)
            .show()
        }
        openUrl(giteePageUrl)
      }
      appendDebugLog("UPDATE_DOWNLOAD", "FAIL", "双源下载失败，已回退到 $giteePageUrl")
    }
  }

  private fun showUpdateDownloadDialog(versionName: String) {
    dismissUpdateDownloadDialog()

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(12))
    }

    val titleView = TextView(this).apply {
      text = "正在准备下载 $versionName"
      textSize = 16f
      setTextColor(Color.parseColor("#1F2937"))
      includeFontPadding = false
    }

    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      isIndeterminate = true
      max = 100
      progress = 0
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).also { params ->
        params.topMargin = dpToPx(14)
      }
    }

    val progressText = TextView(this).apply {
      text = "正在连接下载源…"
      textSize = 13f
      setTextColor(Color.parseColor("#6B7380"))
      includeFontPadding = false
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).also { params ->
        params.topMargin = dpToPx(12)
      }
    }

    container.addView(titleView)
    container.addView(progressBar)
    container.addView(progressText)

    updateDownloadTitleView = titleView
    updateDownloadProgressBar = progressBar
    updateDownloadProgressText = progressText
    updateDownloadDialog = AlertDialog.Builder(this)
      .setTitle("下载安装包")
      .setView(container)
      .setCancelable(false)
      .show()
  }

  private fun updateUpdateDownloadProgress(
    versionName: String,
    sourceLabel: String,
    downloadedBytes: Long,
    totalBytes: Long
  ) {
    updateDownloadTitleView?.text = "正在从 $sourceLabel 下载 $versionName"
    val progressBar = updateDownloadProgressBar ?: return
    val progressText = updateDownloadProgressText ?: return

    if (totalBytes > 0L) {
      progressBar.isIndeterminate = false
      val percent = ((downloadedBytes.coerceAtLeast(0L) * 100) / totalBytes).toInt().coerceIn(0, 100)
      progressBar.progress = percent
      progressText.text = "已下载 ${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)} ($percent%)"
    } else {
      progressBar.isIndeterminate = true
      progressText.text = if (downloadedBytes > 0L) {
        "已下载 ${formatFileSize(downloadedBytes)}"
      } else {
        "正在连接下载源…"
      }
    }
  }

  private fun dismissUpdateDownloadDialog() {
    updateDownloadDialog?.dismiss()
    updateDownloadDialog = null
    updateDownloadTitleView = null
    updateDownloadProgressBar = null
    updateDownloadProgressText = null
  }

  private fun promptInstallDownloadedApk(apkFile: File) {
    pendingApkInstallFile = apkFile
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
      appendDebugLog("UPDATE_INSTALL", "WARN", "缺少未知来源安装权限，等待用户授权")
      AlertDialog.Builder(this)
        .setTitle("需要安装权限")
        .setMessage("安装包已经下载完成，请先允许本应用安装未知来源应用，返回后会自动继续安装。")
        .setNegativeButton("取消", null)
        .setPositiveButton("去开启") { _, _ ->
          val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
          )
          startActivity(intent)
        }
        .show()
      return
    }

    appendDebugLog("UPDATE_INSTALL", "INFO", "安装权限已满足，准备拉起安装器")
    installDownloadedApk(apkFile)
  }

  private fun resumePendingApkInstallIfReady() {
    val apkFile = pendingApkInstallFile ?: return
    if (!apkFile.exists()) {
      pendingApkInstallFile = null
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
      return
    }
    installDownloadedApk(apkFile)
  }

  private fun installDownloadedApk(apkFile: File) {
    if (!apkFile.exists()) {
      pendingApkInstallFile = null
      appendDebugLog("UPDATE_INSTALL", "FAIL", "安装包不存在：${apkFile.absolutePath}")
      Toast.makeText(this, "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show()
      return
    }

    pendingApkInstallFile = null
    appendDebugLog("UPDATE_INSTALL", "SUCCESS", "拉起安装器：${apkFile.name}")
    val contentUri = FileProvider.getUriForFile(
      this,
      "$packageName.fileprovider",
      apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(contentUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(intent)
  }

  @Throws(IOException::class)
  private fun downloadFile(
    fileUrl: String,
    targetFile: File,
    onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
  ) {
    val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      instanceFollowRedirects = true
      connectTimeout = 15000
      readTimeout = 30000
      setRequestProperty("User-Agent", UPDATE_USER_AGENT)
    }

    try {
      connection.connect()
      if (connection.responseCode !in 200..299) {
        throw IOException("HTTP ${connection.responseCode}")
      }

      val totalBytes = connection.contentLengthLong
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var downloadedBytes = 0L
      onProgress?.invoke(0L, totalBytes)

      BufferedInputStream(connection.inputStream).use { input ->
        BufferedOutputStream(targetFile.outputStream()).use { output ->
          while (true) {
            val count = input.read(buffer)
            if (count < 0) {
              break
            }
            output.write(buffer, 0, count)
            downloadedBytes += count
            onProgress?.invoke(downloadedBytes, totalBytes)
          }
          output.flush()
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  @Throws(IOException::class)
  private fun fetchLatestReleaseInfo(sourceLabel: String, releasesUrl: String): AppReleaseInfo {
    val document = org.jsoup.Jsoup.connect(releasesUrl)
      .userAgent(UPDATE_USER_AGENT)
      .timeout(15000)
      .followRedirects(true)
      .get()

    val anchors = document.select("a[href]")
    val apkAnchor = anchors.firstOrNull { anchor ->
      val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
      href.contains(".apk", ignoreCase = true)
    }
    val releaseAnchor = anchors.firstOrNull { anchor ->
      val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
      href.contains("/releases/tag/", ignoreCase = true) || extractVersionName(anchor.text()) != null
    }

    val versionName = listOf(
      apkAnchor?.text(),
      apkAnchor?.attr("title"),
      apkAnchor?.absUrl("href"),
      releaseAnchor?.text(),
      releaseAnchor?.attr("title"),
      releaseAnchor?.absUrl("href"),
      document.title()
    ).mapNotNull(::extractVersionName)
      .firstOrNull()
      ?: throw IOException("未能从 $sourceLabel 发布页中解析版本号")

    val pageUrl = releaseAnchor?.absUrl("href")?.takeIf { it.isNotBlank() } ?: releasesUrl
    val apkUrl = apkAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }

    return AppReleaseInfo(
      sourceLabel = sourceLabel,
      pageUrl = pageUrl,
      versionName = versionName,
      apkUrl = apkUrl
    )
  }

  private fun buildReleaseDownloadCandidates(release: AppReleaseInfo): List<AppReleaseInfo> {
    val candidates = mutableListOf(release)
    val fallback = fetchAlternateReleaseInfo(release)
    if (fallback != null) {
      candidates += fallback
    }
    return candidates.distinctBy { "${it.sourceLabel}|${it.apkUrl}|${it.pageUrl}" }
  }

  private fun fetchAlternateReleaseInfo(release: AppReleaseInfo): AppReleaseInfo? {
    val fallbackSource = if (release.sourceLabel.equals("Gitee", ignoreCase = true)) "GitHub" else "Gitee"
    val fallbackUrl = if (fallbackSource == "Gitee") GITEE_RELEASES_URL else GITHUB_RELEASES_URL
    return try {
      fetchLatestReleaseInfo(fallbackSource, fallbackUrl)
        .takeIf { compareVersionNames(it.versionName, release.versionName) == 0 }
    } catch (_: Throwable) {
      null
    }
  }

  private fun giteeReleasePageUrlForVersion(versionName: String, release: AppReleaseInfo): String {
    if (release.sourceLabel.equals("Gitee", ignoreCase = true) && release.pageUrl.isNotBlank()) {
      return release.pageUrl
    }
    return "$GITEE_RELEASES_URL/tag/v$versionName"
  }

  private fun extractVersionName(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    return Regex("""(?i)v?(\d+(?:\.\d+){1,3})""")
      .find(text)
      ?.groupValues
      ?.getOrNull(1)
  }

  private fun compareVersionNames(left: String, right: String): Int {
    val leftParts = Regex("""\d+""").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
    val rightParts = Regex("""\d+""").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
    val maxSize = max(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
      val leftValue = leftParts.getOrElse(index) { 0 }
      val rightValue = rightParts.getOrElse(index) { 0 }
      if (leftValue != rightValue) {
        return leftValue.compareTo(rightValue)
      }
    }
    return 0
  }

  private fun currentAppVersionName(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo(packageName, 0)
    }
    return packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "0.0.0"
  }

  private fun openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) {
      return "${bytes.coerceAtLeast(0L)} B"
    }
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024.0) {
      return String.format("%.1f KB", kilobytes)
    }
    val megabytes = kilobytes / 1024.0
    if (megabytes < 1024.0) {
      return String.format("%.1f MB", megabytes)
    }
    val gigabytes = megabytes / 1024.0
    return String.format("%.2f GB", gigabytes)
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
    currentWebScreen = WebScreen.LOGIN
    binding.loginPage.visibility = View.VISIBLE
    binding.profilePage.visibility = View.GONE
    binding.homePage.visibility = View.GONE
    binding.homeWebView.visibility = View.GONE
    binding.timetablePage.visibility = View.GONE
    binding.bottomNavGroup.visibility = View.GONE
    updateBottomNavSelection(binding.navProfileButton.id)
    binding.toolbar.title = getString(R.string.toolbar_title_login)
    binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.mutate()?.apply {
      setTint(Color.WHITE)
    }
    applyToolbarLayout()
    updateToolbarNavigationButtonLayout()
  }

  private fun showProfilePage() {
    closeHomeImageViewer(resumeCarousel = false)
    currentWebScreen = WebScreen.PROFILE
    updateProfileWelcome()
    updateAppVersionSummary()
    binding.loginPage.visibility = View.GONE
    binding.profilePage.visibility = View.VISIBLE
    binding.homePage.visibility = View.GONE
    binding.homeWebView.visibility = View.GONE
    binding.timetablePage.visibility = View.GONE
    binding.bottomNavGroup.visibility = View.VISIBLE
    updateBottomNavSelection(binding.navProfileButton.id)
    binding.toolbar.title = getString(R.string.toolbar_title_profile)
    binding.toolbar.navigationIcon = null
    applyToolbarLayout()
    updateToolbarNavigationButtonLayout()
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
        binding.profilePage.visibility = View.GONE
        binding.homePage.visibility = View.VISIBLE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.GONE
      }
      WebScreen.PROFILE -> {
        binding.loginPage.visibility = View.GONE
        binding.profilePage.visibility = View.VISIBLE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.GONE
      }
      WebScreen.LOGIN -> {
        binding.loginPage.visibility = View.VISIBLE
        binding.profilePage.visibility = View.GONE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.GONE
      }
      WebScreen.TIMETABLE -> {
        binding.loginPage.visibility = View.GONE
        binding.profilePage.visibility = View.GONE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.VISIBLE
      }
      WebScreen.EXAM -> {
        binding.loginPage.visibility = View.GONE
        binding.profilePage.visibility = View.GONE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.VISIBLE
      }
      WebScreen.SCORE -> {
        binding.loginPage.visibility = View.GONE
        binding.profilePage.visibility = View.GONE
        binding.homePage.visibility = View.GONE
        binding.homeWebView.visibility = View.GONE
        binding.timetablePage.visibility = View.VISIBLE
      }
    }

    val hideBottomNav = screen == WebScreen.TIMETABLE || screen == WebScreen.EXAM || screen == WebScreen.SCORE || screen == WebScreen.LOGIN
    binding.bottomNavGroup.visibility = if (hideBottomNav || homeViewerVisible) View.GONE else View.VISIBLE
    if (screen == WebScreen.HOME) {
      updateBottomNavSelection(binding.navHomeButton.id)
    } else if (screen == WebScreen.PROFILE || screen == WebScreen.LOGIN) {
      updateBottomNavSelection(binding.navProfileButton.id)
    }
    binding.toolbar.title = when (screen) {
      WebScreen.HOME -> getString(R.string.toolbar_title_home)
      WebScreen.PROFILE -> getString(R.string.toolbar_title_profile)
      WebScreen.LOGIN -> getString(R.string.toolbar_title_login)
      WebScreen.TIMETABLE -> getString(R.string.toolbar_title_timetable)
      WebScreen.EXAM -> getString(R.string.toolbar_title_exam)
      WebScreen.SCORE -> "成绩查询"
    }
    binding.toolbar.navigationIcon = if (screen == WebScreen.TIMETABLE || screen == WebScreen.EXAM || screen == WebScreen.SCORE || screen == WebScreen.LOGIN) {
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
    } else if (screen == WebScreen.PROFILE) {
      binding.profilePage.requestFocus()
      mainHandler.removeCallbacks(homeCarouselRunnable)
    } else if (screen == WebScreen.LOGIN) {
      binding.loginPage.requestFocus()
      mainHandler.removeCallbacks(homeCarouselRunnable)
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
    binding.homeOpenExamButton.setOnClickListener { showCachedExamSchedule() }
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
    val usesPrimaryPageToolbar = currentWebScreen == WebScreen.HOME || currentWebScreen == WebScreen.PROFILE
    val extraBottom = if (usesPrimaryPageToolbar) dpToPx(10) else 0
    val minHeight = if (usesPrimaryPageToolbar) dpToPx(56) else 0

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

  private fun showCachedExamSchedule() {
    showingLiveTimetable = false
    applyWebScreen(WebScreen.EXAM)
    loadExamPageWithLatestData()
  }

  private fun showCachedScorePage() {
    showingLiveTimetable = false
    applyWebScreen(WebScreen.SCORE)
    loadScorePageWithLatestData()
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

  private fun refreshGeneratedCacheAfterStartup() {
    syncAssetExportId()
    CourseNotificationScheduler.sync(this)
    ExamOngoingNotificationScheduler.sync(this)
    renderedHomeSignature = null

    if (currentWebScreen == WebScreen.HOME) {
      presentHomePage()
      return
    }

    if (currentWebScreen == WebScreen.TIMETABLE && !showingLiveTimetable) {
      showCachedTimetableOrEmpty()
      return
    }

    if (currentWebScreen == WebScreen.EXAM) {
      showCachedExamSchedule()
      return
    }

    if (currentWebScreen == WebScreen.SCORE) {
      showCachedScorePage()
    }
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

  private fun hasUsableExamCache(cacheJsonFile: File): Boolean {
    if (!cacheJsonFile.exists() || cacheJsonFile.length() <= 0L) {
      return false
    }

    return try {
      val raw = cacheJsonFile.readText(Charsets.UTF_8).trim()
      raw.startsWith("[") && raw.endsWith("]") && JSONArray(raw).length() >= 0
    } catch (_: Exception) {
      false
    }
  }

  private fun hasUsableScoreCache(cacheJsonFile: File): Boolean {
    return hasUsableExamCache(cacheJsonFile)
  }

  private fun loadExamPageWithLatestData() {
    val templateHtml = runCatching {
      assets.open("exam-view.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    if (templateHtml.isNullOrBlank()) {
      binding.contentWebView.stopLoading()
      binding.contentWebView.clearHistory()
      binding.contentWebView.clearCache(true)
      binding.contentWebView.loadUrl("${HOME_ASSET_BASE_URL}exam-view.html?v=${System.currentTimeMillis()}")
      return
    }

    val latestJson = readLatestExamJson()
    val html = if (latestJson.isNullOrBlank()) {
      templateHtml
    } else {
      injectExamJsonIntoTemplate(templateHtml, latestJson)
    }

    binding.contentWebView.stopLoading()
    binding.contentWebView.clearHistory()
    binding.contentWebView.clearCache(true)
    binding.contentWebView.loadDataWithBaseURL(
      HOME_ASSET_BASE_URL,
      html,
      "text/html",
      "utf-8",
      null
    )
  }

  private fun readLatestExamJson(): String? {
    val runtimeFile = File(filesDir, EXAM_JSON_FILE)
    if (hasUsableExamCache(runtimeFile)) {
      return runtimeFile.readText(Charsets.UTF_8)
    }

    return runCatching {
      assets.open(EXAM_JSON_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
  }

  private fun loadScorePageWithLatestData() {
    val templateHtml = runCatching {
      assets.open("score-view.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    if (templateHtml.isNullOrBlank()) {
      binding.contentWebView.stopLoading()
      binding.contentWebView.clearHistory()
      binding.contentWebView.clearCache(true)
      binding.contentWebView.loadUrl("${HOME_ASSET_BASE_URL}score-view.html?v=${System.currentTimeMillis()}")
      return
    }

    val latestJson = readLatestScoreJson()
    val html = if (latestJson.isNullOrBlank()) {
      templateHtml
    } else {
      injectScoreJsonIntoTemplate(templateHtml, latestJson)
    }

    binding.contentWebView.stopLoading()
    binding.contentWebView.clearHistory()
    binding.contentWebView.clearCache(true)
    binding.contentWebView.loadDataWithBaseURL(
      HOME_ASSET_BASE_URL,
      html,
      "text/html",
      "utf-8",
      null
    )
  }

  private fun readLatestScoreJson(): String? {
    val runtimeFile = File(filesDir, SCORE_JSON_FILE)
    if (hasUsableScoreCache(runtimeFile)) {
      return runtimeFile.readText(Charsets.UTF_8)
    }

    return runCatching {
      assets.open(SCORE_JSON_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
  }

  private fun injectExamJsonIntoTemplate(templateHtml: String, examsJson: String): String {
    val pattern = Regex("""const exams = .*?;""", setOf(RegexOption.DOT_MATCHES_ALL))
    return if (pattern.containsMatchIn(templateHtml)) {
      templateHtml.replace(pattern, "const exams = ${serializeForScript(examsJson)};")
    } else {
      templateHtml
    }
  }

  private fun injectScoreJsonIntoTemplate(templateHtml: String, scoresJson: String): String {
    val pattern = Regex("""const rawScores = .*?;""", setOf(RegexOption.DOT_MATCHES_ALL))
    return if (pattern.containsMatchIn(templateHtml)) {
      templateHtml.replace(pattern, "const rawScores = ${serializeForScript(scoresJson)};")
    } else {
      templateHtml
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
      url.contains("exam-view", ignoreCase = true) -> {
        showCachedExamSchedule()
        true
      }
      url.contains("score-view", ignoreCase = true) -> {
        showCachedScorePage()
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
      button.elevation = 0f
      button.translationZ = 0f
      button.stateListAnimator = null
      button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
      button.rippleColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
      button.isPressed = false
      button.isSelected = selected
    }
  }

  private fun currentHomeSignature(): String {
    val homeCacheFile = File(filesDir, GENERATED_HOME_HTML_FILE)
    val cacheJsonFile = File(filesDir, CACHE_JSON_FILE)
    val examJsonFile = File(filesDir, EXAM_JSON_FILE)
    val scoreJsonFile = File(filesDir, SCORE_JSON_FILE)
    val homePart = if (homeCacheFile.exists()) "${homeCacheFile.length()}:${homeCacheFile.lastModified()}" else "missing"
    val jsonPart = if (cacheJsonFile.exists()) "${cacheJsonFile.length()}:${cacheJsonFile.lastModified()}" else "missing"
    val examPart = if (examJsonFile.exists()) "${examJsonFile.length()}:${examJsonFile.lastModified()}" else "missing"
    val scorePart = if (scoreJsonFile.exists()) "${scoreJsonFile.length()}:${scoreJsonFile.lastModified()}" else "missing"
    return listOf(currentAssetExportId ?: "no-export", homePart, jsonPart, examPart, scorePart).joinToString("|")
  }

  private fun renderNativeHome() {
    currentHomeImages = loadHomeImages()
    currentHomeImageIndex = currentHomeImageIndex.coerceIn(0, max(0, currentHomeImages.lastIndex))
    setHomeImageIndex(currentHomeImageIndex)

    val exams = loadExamsFromCacheJson()
    val recentExams = buildRecentExams(exams)
    binding.homeRecentExamCard.visibility = if (recentExams.isEmpty()) View.GONE else View.VISIBLE
    if (recentExams.isNotEmpty()) {
      binding.homeRecentExamHint.visibility = View.GONE
      binding.homeRecentExamHint.text = getString(R.string.home_recent_exam_updated)
      renderRecentExams(recentExams)
    } else {
      binding.homeRecentExamList.removeAllViews()
    }

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
      if ((currentWebScreen == WebScreen.HOME || currentWebScreen == WebScreen.PROFILE) && binding.loginPage.visibility != View.VISIBLE) View.VISIBLE else View.GONE
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
    val candidatePaths = linkedSetOf(assetPath).apply {
      if (assetPath.contains("-thumb.")) {
        add(assetPath.replace("-thumb.", "-detail."))
        add(assetPath.replace("-thumb.", "."))
      } else if (assetPath.contains("-detail.")) {
        add(assetPath.replace("-detail.", "."))
      }
    }

    val bitmap = candidatePaths.firstNotNullOfOrNull { candidatePath ->
      runCatching {
        assets.open(candidatePath).use { BitmapFactory.decodeStream(it) }
      }.getOrNull()
    } ?: return null

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
        setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(2))
        layoutParams = GridLayout.LayoutParams().apply {
          width = 0
          columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
          setMargins(0, 0, 0, dpToPx(8))
        }
      }

      val icon = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
        setImageResource(item.iconRes)
        background = null
        alpha = 1f
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
      }

      val label = TextView(this).apply {
        text = item.label
        textSize = 14f
        setTextColor(Color.parseColor("#6B7380"))
        gravity = android.view.Gravity.CENTER
        minLines = 2
        includeFontPadding = false
      }

      itemView.addView(icon)
      itemView.addView(label)
      itemView.setOnClickListener {
        if (item.key == "schedule") {
          showCachedTimetable()
        } else if (item.key == "exam") {
          showCachedExamSchedule()
        } else if (item.key == "score") {
          showCachedScorePage()
        } else if (item.key == "refresh") {
          val user = prefs.getString(PREF_USERNAME, "")
          val pwd = prefs.getString(PREF_PASSWORD, "")
          if (user.isNullOrBlank() || pwd.isNullOrBlank()) {
             Toast.makeText(this, "请先在个人中心填写账号密码", Toast.LENGTH_SHORT).show()
          } else {
             isAutoUpdating = true
             autoUpdateFailedAttempts = 0
             Toast.makeText(this, "开始后台自动更新课表...", Toast.LENGTH_SHORT).show()
             bootstrapLoginSession(forceReload = true)
          }
        } else {
          Toast.makeText(this, "该功能入口已预留，暂未实现", Toast.LENGTH_SHORT).show()
        }
      }
      binding.homeMenuGrid.addView(itemView)
    }
  }

  private fun loadCoursesFromCacheJson(): List<TimetableCourse> {
    return TimetableScheduleHelper.loadCoursesFromCacheJson(this)
  }

  private fun loadExamsFromCacheJson(): List<ExamArrangement> {
    val raw = readLatestExamJson()?.trim().orEmpty()
    if (raw.isBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
          ExamArrangement(
            index = item.optInt("index", index + 1),
            examSession = item.optString("examSession"),
            courseCode = item.optString("courseCode"),
            courseName = item.optString("courseName"),
            examTime = item.optString("examTime"),
            examRoom = item.optString("examRoom"),
            seatNumber = item.optString("seatNumber"),
            teacher = item.optString("teacher"),
            rawText = item.optString("rawText")
          )
        )
      }
    }
  }

  private fun parseExamTimeRange(value: String): ParsedExamTime? {
    val match = EXAM_TIME_REGEX.find(value.trim()) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    val startTime = runCatching { LocalTime.parse(match.groupValues[2]) }.getOrNull() ?: return null
    val endTime = runCatching { LocalTime.parse(match.groupValues[3]) }.getOrNull() ?: return null
    return ParsedExamTime(
      date = date,
      startTime = startTime,
      endTime = endTime
    )
  }

  private fun buildRecentExams(exams: List<ExamArrangement>): List<HomeRecentExamEntry> {
    if (exams.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val now = LocalTime.now()
    return exams.mapNotNull { exam ->
      val parsed = parseExamTimeRange(exam.examTime) ?: return@mapNotNull null
      if (parsed.date.isBefore(today)) return@mapNotNull null
      if (parsed.date == today && parsed.endTime.isBefore(now)) return@mapNotNull null
      NormalizedExam(
        exam = exam,
        date = parsed.date,
        startTime = parsed.startTime,
        endTime = parsed.endTime
      )
    }
      .sortedWith(compareBy<NormalizedExam> { it.date }.thenBy { it.startTime })
      .take(2)
      .map { item ->
        val daysUntil = ChronoUnit.DAYS.between(today, item.date).toInt().coerceAtLeast(0)
        val roomSeat = listOf(
          item.exam.examRoom.trim().ifBlank { "待定" },
          item.exam.seatNumber.trim().ifBlank { "" }
        ).filter { it.isNotBlank() }.joinToString(" ")
        HomeRecentExamEntry(
          displayDay = HOME_WEEK_TITLES[(item.date.dayOfWeek.value - 1) % HOME_WEEK_TITLES.size],
          title = item.exam.courseName.ifBlank { "未命名考试" },
          meta = "${item.date} ${item.startTime} 剩${daysUntil}天",
          roomSeat = roomSeat.ifBlank { "待定" },
          isToday = daysUntil == 0,
          isAlert = daysUntil <= 1
        )
      }
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
          val majorLabel = formatMajorPeriodLabel(course.startPeriod, course.endPeriod)
          result += HomeRecentEntry(
            displayDay = if (offset == 0) "今日" else "明日",
            title = course.course.courseName,
            meta = "$majorLabel ${PERIOD_SLOTS[course.startPeriod]?.first.orEmpty()}-${PERIOD_SLOTS[course.endPeriod]?.second.orEmpty()}",
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

  private fun formatMajorPeriodLabel(startPeriod: Int, endPeriod: Int): String {
    if (startPeriod == 14 || endPeriod == 14) {
      return "线上"
    }

    val majorIndex = when (startPeriod) {
      in 1..3 -> 1
      in 4..5 -> 2
      in 6..7 -> 3
      in 8..10 -> 4
      in 11..13 -> 5
      else -> startPeriod
    }
    return "第${majorIndex}大节"
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

  private fun refreshCaptchaInWebView(retryCount: Int = 0) {
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
      mainHandler.postDelayed({ fetchCaptchaFromWebView(retryCount) }, 500)
    }
  }

  private fun fetchCaptchaFromWebView(retryCount: Int = 0) {
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

      loadCaptchaImage(relativeUrl, retryCount)
    }
  }

  private fun loadCaptchaImage(relativeUrl: String, retryCount: Int = 0) {
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
          
          if (bitmap != null) {
            val processedBitmap = preprocessCaptcha(bitmap)
            val image = InputImage.fromBitmap(processedBitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
              .addOnSuccessListener { visionText ->
                val text = visionText.text.replace(Regex("[^a-zA-Z0-9]"), "")
                if (text.length == 4) {
                  mainHandler.post {
                    binding.captchaInput.editText?.setText(text)
                    updateStatus("验证码识别成功")
                    if (isAutoUpdating) {
                      submitLogin()
                    }
                  }
                } else if (retryCount < 5) {
                  mainHandler.post { 
                    updateStatus("验证码识别失败，正在重试...")
                    refreshCaptchaInWebView(retryCount + 1) 
                  }
                } else {
                  mainHandler.post {
                    binding.captchaInput.editText?.setText(text)
                    updateStatus("验证码识别达到最大重试次数")
                  }
                }
              }
              .addOnFailureListener {
                if (retryCount < 5) {
                  mainHandler.post { 
                    updateStatus("验证码识别异常，正在重试...")
                    refreshCaptchaInWebView(retryCount + 1) 
                  }
                }
              }
          }
        }
      } catch (error: Exception) {
        mainHandler.post {
          updateStatus(getString(R.string.status_captcha_failed, error.message ?: "unknown"))
        }
      }
    }
  }

  private fun preprocessCaptcha(src: Bitmap): Bitmap {
    val scale = 3f
    val scaledWidth = (src.width * scale).toInt()
    val scaledHeight = (src.height * scale).toInt()
    val scaledBitmap = Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, true)
    
    val result = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    val paint = android.graphics.Paint()
    val colorMatrix = android.graphics.ColorMatrix().apply {
      setSaturation(0f)
    }
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

    val pixels = IntArray(scaledWidth * scaledHeight)
    result.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
    for (i in pixels.indices) {
      val p = pixels[i]
      val r = Color.red(p)
      val g = Color.green(p)
      val b = Color.blue(p)
      val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
      pixels[i] = if (gray > 165) Color.WHITE else Color.BLACK
    }
    result.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
    return result
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
          if (isAutoUpdating) {
            autoUpdateFailedAttempts++
            updateStatus("登录失败，正在进行第 ${autoUpdateFailedAttempts} 次重试...")
          }
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
          val examSyncResult = runCatching { syncExamCacheFromSession(courses) }
            .onFailure { appendDebugLog("EXAM", "FAIL", it.message ?: "unknown") }
          val scoreSyncResult = runCatching { syncScoreCacheFromSession() }
            .onFailure { appendDebugLog("SCORE", "FAIL", it.message ?: "unknown") }

          mainHandler.post {
            cacheCaptureInProgress = false
            val examCount = examSyncResult.getOrNull()
            val scoreCount = scoreSyncResult.getOrNull()
            if (examCount != null && scoreCount != null) {
              updateStatus("本地缓存已更新，共解析 ${courses.size} 条课程，${examCount} 场考试，${scoreCount} 条成绩。")
            } else if (examCount != null) {
              updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程，${examCount} 场考试；成绩同步失败。")
            } else if (scoreCount != null) {
              updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程，${scoreCount} 条成绩；考试安排同步失败。")
            } else {
              updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程；考试安排和成绩同步失败。")
            }
            CourseNotificationScheduler.sync(this@MainActivity)
            ExamOngoingNotificationScheduler.sync(this@MainActivity)
            if (isAutoUpdating) {
              val msg = if (autoUpdateFailedAttempts == 0) "更新成功 (1次通过)" else "更新成功 (失败 ${autoUpdateFailedAttempts} 次后)"
              Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
              isAutoUpdating = false
            }
            showCachedTimetable()
          }
        } catch (error: Exception) {
          mainHandler.post {
            cacheCaptureInProgress = false
            updateStatus("缓存同步失败：${error.message ?: "unknown"}")
            if (isAutoUpdating) {
              isAutoUpdating = false
            }
          }
        }
      }
    }
  }

  private fun renderRecentExams(items: List<HomeRecentExamEntry>) {
    binding.homeRecentExamList.removeAllViews()
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
              else -> "#7D8798"
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
                else -> "#E4ECFB"
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

      val roomSeat = TextView(this).apply {
        text = item.roomSeat
        textSize = 16f
        setTextColor(Color.parseColor("#8F959C"))
      }

      row.addView(badge)
      row.addView(middle)
      row.addView(roomSeat)
      binding.homeRecentExamList.addView(row)
      if (index < items.lastIndex) {
        binding.homeRecentExamList.addView(View(this).apply {
          layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(1)
          )
          setBackgroundColor(Color.parseColor("#33B0BCCB"))
        })
      }
    }
  }

  private fun syncExamCacheFromSession(courses: List<TimetableCourse>): Int {
    appendDebugLog("EXAM", "START", "开始同步考试缓存，课程数=${courses.size}")
    val exams = fetchExamArrangements(courses)
    File(filesDir, EXAM_JSON_FILE).writeText(ExamRenderer.toJson(exams), Charsets.UTF_8)
    appendDebugLog("EXAM", "SUCCESS", "考试缓存写入完成，共 ${exams.size} 场")
    return exams.size
  }

  private fun syncScoreCacheFromSession(): Int {
    appendDebugLog("SCORE", "START", "开始同步成绩缓存")
    val scores = fetchScoreRecords()
    File(filesDir, SCORE_JSON_FILE).writeText(scores.toString(), Charsets.UTF_8)
    appendDebugLog("SCORE", "SUCCESS", "成绩缓存写入完成，共 ${scores.length()} 条")
    return scores.length()
  }

  private fun fetchExamArrangements(courses: List<TimetableCourse>): List<ExamArrangement> {
    val queryDocument = fetchExamQueryDocument()
    val form = queryDocument.selectFirst("form[name=ksapQueryForm]") ?: queryDocument.selectFirst("form")
      ?: throw IllegalStateException("未找到考试查询表单")
    val method = form.attr("method").ifBlank { "post" }
    val actionUrl = resolveExamListUrl(form)
    val parameters = extractExamFormParameters(form)
    val examBytes = submitExamQuery(actionUrl, method, parameters)
    return ExamParser.parseBytes(examBytes, actionUrl, courses)
  }

  private fun fetchScoreRecords(): JSONArray {
    appendDebugLog("SCORE_FETCH", "START", "开始请求成绩页")
    val bytes = withSessionConnection(SCORE_LIST_URL, method = "GET", referer = TIMETABLE_URL) { connection ->
      val responseCode = connection.responseCode
      appendDebugLog(
        "SCORE_FETCH",
        if (responseCode in 200..299) "INFO" else "WARN",
        "响应码=$responseCode contentType=${connection.contentType ?: "-"}"
      )
      val input = if (responseCode in 200..299) {
        connection.inputStream
      } else {
        connection.errorStream ?: connection.inputStream
      }
      input.use { it.readBytes() }
    }
    appendDebugLog("SCORE_FETCH", "INFO", "成绩页响应体大小=${bytes.size} bytes")
    val document = org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, SCORE_LIST_URL)
    appendDebugLog("SCORE_FETCH", "INFO", "成绩页标题=${document.title().ifBlank { "-" }}")
    return parseScoreDocument(document)
  }

  private fun parseScoreDocument(document: org.jsoup.nodes.Document): JSONArray {
    val rows = document.select("#dataList tr")
    if (rows.isEmpty()) {
      val title = document.title()
      appendDebugLog("SCORE_PARSE", "FAIL", "未找到成绩表格，页面标题=${title.ifBlank { "-" }}")
      if (title.contains("登录") || title.contains("login", ignoreCase = true)) {
        throw IllegalStateException("未获取到成绩列表，会话可能已过期。当前页面: $title")
      }
      throw IllegalStateException("未在成绩页面中找到 #dataList 表格。当前页面: $title")
    }
    appendDebugLog("SCORE_PARSE", "INFO", "检测到成绩表格行数=${rows.size}")
    val result = JSONArray()

    rows.drop(1).forEachIndexed { index, row ->
      val cells = row.select("> td")
      if (cells.size < 11) {
        return@forEachIndexed
      }

      val item = JSONObject().apply {
        put("index", cleanInlineText(cells.getOrNull(0)?.text().orEmpty()).toIntOrNull() ?: (result.length() + 1))
        put("semester", cleanInlineText(cells.getOrNull(1)?.text().orEmpty()))
        put("courseCode", cleanInlineText(cells.getOrNull(2)?.text().orEmpty()))
        put("courseName", cleanInlineText(cells.getOrNull(3)?.text().orEmpty()))
        put("score", cleanInlineText(cells.getOrNull(4)?.text().orEmpty()))
        put("scoreIdentifier", cleanInlineText(cells.getOrNull(5)?.text().orEmpty()))
        put("credits", cleanInlineText(cells.getOrNull(6)?.text().orEmpty()))
        put("totalHours", cleanInlineText(cells.getOrNull(7)?.text().orEmpty()))
        put("assessmentMethod", cleanInlineText(cells.getOrNull(8)?.text().orEmpty()))
        put("courseAttribute", cleanInlineText(cells.getOrNull(9)?.text().orEmpty()))
        put("courseNature", cleanInlineText(cells.getOrNull(10)?.text().orEmpty()))
        put("isHighlighted", cells.getOrNull(4)?.attr("style")?.contains("red", ignoreCase = true) == true)
        put(
          "rawText",
          buildString {
            cells.forEachIndexed { cellIndex, cell ->
              if (cellIndex > 0) append('\n')
              append(cleanInlineText(cell.text()))
            }
          }
        )
      }

      val courseName = item.optString("courseName")
      val semester = item.optString("semester")
      val score = item.optString("score")
      if (courseName.isBlank() && semester.isBlank() && score.isBlank()) {
        return@forEachIndexed
      }

      result.put(item)
    }

    appendDebugLog("SCORE_PARSE", "SUCCESS", "成绩解析完成，共 ${result.length()} 条")
    return result
  }

  private fun fetchExamQueryDocument() =
    withSessionConnection(EXAM_QUERY_URL, method = "GET") { connection ->
      val bytes = connection.inputStream.use { it.readBytes() }
      org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, EXAM_QUERY_URL)
    }

  private fun resolveExamListUrl(form: org.jsoup.nodes.Element): String {
    val rawAction = form.absUrl("action").ifBlank { form.attr("action") }
    val resolved = when {
      rawAction.isBlank() -> EXAM_LIST_URL
      rawAction.contains("xsksap_list", ignoreCase = true) -> rawAction
      else -> URL(URL(EXAM_QUERY_URL), rawAction).toString()
    }
    return if (resolved.contains("xsksap_list", ignoreCase = true)) resolved else EXAM_LIST_URL
  }

  private fun extractExamFormParameters(form: org.jsoup.nodes.Element): Map<String, String> {
    val parameters = linkedMapOf<String, String>()

    form.select("input[name], textarea[name], select[name]").forEach { field ->
      val name = field.attr("name").trim()
      if (name.isBlank()) {
        return@forEach
      }

      when (field.tagName().lowercase()) {
        "select" -> {
          parameters[name] = resolveSelectValue(field)
        }
        "textarea" -> {
          parameters[name] = field.text()
        }
        else -> {
          val type = field.attr("type").lowercase()
          when (type) {
            "checkbox", "radio" -> if (field.hasAttr("checked")) {
              parameters[name] = field.attr("value").ifBlank { "on" }
            }
            "submit", "button", "file", "image", "reset" -> Unit
            else -> parameters[name] = field.attr("value")
          }
        }
      }
    }

    val semesterField = form.selectFirst("select[name=xnxqid]")
    if (semesterField != null) {
      parameters["xnxqid"] = resolveSelectValue(semesterField)
    } else if (parameters["xnxqid"].isNullOrBlank()) {
      parameters["xnxqid"] = EXAM_DEFAULT_SEMESTER
    }

    return parameters
  }

  private fun resolveSelectValue(select: org.jsoup.nodes.Element): String {
    val options = select.select("option")
    val preferred = options.firstOrNull { option ->
      option.hasAttr("selected") && option.attr("value").isNotBlank()
    }?.attr("value")
      ?: options.firstOrNull { option ->
        option.attr("value") == EXAM_DEFAULT_SEMESTER
      }?.attr("value")
      ?: options.firstOrNull { option ->
        option.attr("value").isNotBlank()
      }?.attr("value")
      ?: options.firstOrNull()?.attr("value")
      ?: ""

    return preferred.ifBlank { EXAM_DEFAULT_SEMESTER }
  }

  private fun submitExamQuery(
    url: String,
    method: String,
    parameters: Map<String, String>
  ): ByteArray {
    val normalizedMethod = method.uppercase()
    val requestUrl = if (normalizedMethod == "GET" && parameters.isNotEmpty()) {
      val separator = if (url.contains("?")) "&" else "?"
      url + separator + encodeFormBody(parameters)
    } else {
      url
    }

    return withSessionConnection(requestUrl, method = normalizedMethod, referer = EXAM_QUERY_URL) { connection ->
      if (method.equals("post", ignoreCase = true)) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { output ->
          output.write(encodeFormBody(parameters).toByteArray(Charsets.UTF_8))
        }
      }

      connection.inputStream.use { it.readBytes() }
    }
  }

  private inline fun <T> withSessionConnection(
    url: String,
    method: String,
    referer: String? = null,
    block: (HttpURLConnection) -> T
  ): T {
    val connection = openSessionConnection(url, method, referer)
    return try {
      block(connection)
    } finally {
      connection.disconnect()
    }
  }

  private fun openSessionConnection(
    url: String,
    method: String,
    referer: String? = null
  ): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method.uppercase()
    connection.useCaches = false
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 10000
    connection.readTimeout = 10000
    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36")
    referer?.let { connection.setRequestProperty("Referer", it) }
    val cookie = buildCookieHeader(url)
    if (cookie.isNotBlank()) {
      connection.setRequestProperty("Cookie", cookie)
    }
    appendDebugLog(
      "HTTP",
      "INFO",
      "${method.uppercase()} $url referer=${referer ?: "-"} cookieLength=${cookie.length}"
    )
    return connection
  }

  private fun buildCookieHeader(targetUrl: String): String {
    val manager = CookieManager.getInstance()
    return listOf(
      targetUrl,
      EXAM_QUERY_URL,
      EXAM_LIST_URL,
      TIMETABLE_URL,
      LOGIN_URL
    ).mapNotNull { candidate ->
      manager.getCookie(candidate)?.trim()
    }.filter { it.isNotBlank() }
      .distinct()
      .joinToString("; ")
  }

  private fun encodeFormBody(parameters: Map<String, String>): String =
    parameters.entries.joinToString("&") { (key, value) ->
      "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

  private fun updateStatus(message: String) {
    binding.statusText.text = message
    appendDebugLog("STATUS", "INFO", message)
  }

  private fun appendDebugLog(scope: String, status: String, message: String) {
    AppDebugLog.append(this, scope, status, message)
  }

  private fun restoreSavedCredentials() {
    binding.usernameInput.editText?.setText(prefs.getString(PREF_USERNAME, "").orEmpty())
    binding.passwordInput.editText?.setText(prefs.getString(PREF_PASSWORD, "").orEmpty())
    updateProfileWelcome()
  }

  private fun updateProfileWelcome() {
    val username = prefs.getString(PREF_USERNAME, "").orEmpty().trim()
    binding.profileUsernameText.text = if (username.isBlank()) {
      getString(R.string.profile_welcome_guest)
    } else {
      getString(R.string.profile_welcome_format, username)
    }
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
    updateProfileWelcome()
  }

  private fun syncAssetExportId() {
    val currentExportId = readAssetExportId() ?: return
    currentAssetExportId = currentExportId
    val courses = loadCoursesFromCacheJson()
    if (courses.isNotEmpty()) {
      runCatching {
        File(filesDir, GENERATED_HOME_HTML_FILE).writeText(
          TimetableRenderer.toHomeHtml(this, courses),
          Charsets.UTF_8
        )
        File(filesDir, GENERATED_CACHE_HTML_FILE).writeText(
          TimetableRenderer.toHtml(this, courses),
          Charsets.UTF_8
        )
      }
    } else {
      File(filesDir, GENERATED_HOME_HTML_FILE).delete()
      File(filesDir, GENERATED_CACHE_HTML_FILE).delete()
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
