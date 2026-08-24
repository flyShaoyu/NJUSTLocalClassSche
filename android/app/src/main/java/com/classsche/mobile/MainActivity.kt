package com.classsche.mobile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.classsche.mobile.databinding.ActivityMainBinding
import androidx.core.app.NotificationCompat
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
  private var headlessLoginInProgress = false
  private var lastAutoScoreSyncElapsed = 0L
  private var pendingNotificationToggleTarget: NotificationToggleTarget? = null
  private var updateCheckInProgress = false
  private var pendingManualUpdateResult = false
  private var pendingApkInstallFile: File? = null
  private var updateDownloadDialog: AlertDialog? = null
  private var updateDownloadTitleView: TextView? = null
  private var updateDownloadNotesView: TextView? = null
  private var updateDownloadProgressBar: ProgressBar? = null
  private var updateDownloadProgressText: TextView? = null
  private var lastAutoUpdateCheckElapsed = 0L
  private var authTimetableCaptureShouldShowCache = true
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
  private data class TimetableSemesterSnapshot(
    val availableSemesters: List<String>,
    val currentSemester: String,
    val desiredSemester: String,
    val switched: Boolean,
    val weekFilter: String
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

  private data class HomeRecentScoreEntry(
    val displayTag: String,
    val title: String,
    val meta: String,
    val score: String,
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
    val apkUrl: String?,
    val releaseNotes: String
  )

  private data class ParsedReleaseEntry(
    val sourceLabel: String,
    val versionName: String,
    val pageUrl: String,
    val apkUrl: String?,
    val releaseNotes: String
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
    private const val LOGIN_URL = "http://202.119.81.112:8080"
    private const val TIMETABLE_URL = "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do"
    private const val HOME_ASSET_BASE_URL = "file:///android_asset/"
    private const val GENERATED_HOME_HTML_FILE = "home-view-generated.html"
    private const val GENERATED_CACHE_HTML_FILE = "timetable-view-generated.html"
    private const val CACHE_JSON_FILE = "timetable.json"
    private const val EXAM_JSON_FILE = "exam-list.json"
    private const val SCORE_JSON_FILE = "score-list.json"
    private const val HEADLESS_SCORE_TEST_FILE = "headless-score-test.json"
    private const val SCORE_UPDATE_META_FILE = "score-update-meta.json"
    private const val CACHE_RAW_HTML_FILE = "timetable.raw.html"
    private const val EXAM_QUERY_URL = "http://202.119.81.112:9080/njlgdx/xsks/xsksap_query"
    private const val EXAM_LIST_URL = "http://202.119.81.112:9080/njlgdx/xsks/xsksap_list"
    private const val SCORE_LIST_URL = "http://202.119.81.112:9080/njlgdx/kscj/cjcx_list"
    private const val GITEE_HOME_URL = "https://gitee.com/flyshaoyu/njust_localclasssche"
    private const val GITHUB_HOME_URL = "https://github.com/flyShaoyu/NJUSTLocalClassSche"
    private const val GITEE_RELEASES_URL = "https://gitee.com/flyshaoyu/njust_localclasssche/releases"
    private const val GITHUB_RELEASES_URL = "https://github.com/flyShaoyu/NJUSTLocalClassSche/releases"
    private const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/flyShaoyu/NJUSTLocalClassSche/releases?per_page=20"
    private const val UPDATE_USER_AGENT = "Mozilla/5.0 ClassScheMobile"
    private const val UPDATE_FETCH_CONNECT_TIMEOUT_MS = 10000
    private const val UPDATE_FETCH_READ_TIMEOUT_MS = 15000
    private const val UPDATE_NOTES_CONNECT_TIMEOUT_MS = 3000
    private const val UPDATE_NOTES_READ_TIMEOUT_MS = 5000
    private const val SCORE_UPDATE_CHANNEL_ID = "classsche_score_update_v1"
    private const val SCORE_UPDATE_NOTIFICATION_ID = 3101
    private const val EXAM_DEFAULT_SEMESTER = "2025-2026-2"
    private const val PREF_USERNAME = "username"
    private const val PREF_PASSWORD = "password"
    private const val PREF_ASSET_EXPORT_ID = "asset_export_id"
    private const val PREF_TIMETABLE_CACHE_PARSER_VERSION = "timetable_cache_parser_version"
    private const val CURRENT_TIMETABLE_CACHE_PARSER_VERSION = 2
    private const val PREF_UPDATE_AVAILABLE_VERSION = "update_available_version"
    private const val PREF_UPDATE_AVAILABLE_SOURCE = "update_available_source"
    private const val PREF_UPDATE_PROMPTED_VERSION = "update_prompted_version"
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
    TimetableSemesterStore.refreshCatalogFromRawHtmlIfNeeded(this)
    CourseNotificationScheduler.sync(this)
    ExamOngoingNotificationScheduler.sync(this)
    HeadlessScoreSyncScheduler.scheduleNext(this)

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
    refreshUpdateBadge()
    resumePendingApkInstallIfReady()
    refreshGeneratedCacheAfterStartup()
    triggerPendingTimetableSemesterRefreshIfNeeded()
    triggerScoreSyncOnAppOpenIfNeeded()
    triggerAutoUpdateCheckIfNeeded()
  }

  private fun triggerAutoUpdateCheckIfNeeded() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastAutoUpdateCheckElapsed < 15_000L) {
      return
    }
    lastAutoUpdateCheckElapsed = now
    checkForAppUpdate(silent = true)
  }

  private fun triggerPendingTimetableSemesterRefreshIfNeeded() {
    if (!TimetableSemesterStore.consumeRefreshRequest(this)) {
      return
    }
    authTimetableCaptureShouldShowCache = false
    appendDebugLog("TIMETABLE_SEMESTER", "START", "检测到课表学期变更，开始静默刷新课表缓存")
    updateStatus("课表学期已变更，正在刷新课表缓存…")
    binding.authWebView.loadUrl(TIMETABLE_URL)
  }

  private fun triggerScoreSyncOnAppOpenIfNeeded() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastAutoScoreSyncElapsed < 15_000L) {
      return
    }
    lastAutoScoreSyncElapsed = now
    val skipReason = ScoreSyncSettings.skipReason(this)
    HeadlessScoreSyncScheduler.scheduleNext(this)
    if (skipReason != null) {
      appendDebugLog("HEADLESS_SCORE_SYNC", "SKIP", "应用打开时跳过自动成绩检查：$skipReason")
      return
    }
    HeadlessScoreSyncManager.runSync(this, reason = "APP_OPEN") { result ->
      if (result.status != HeadlessScoreSyncManager.Status.SUCCESS) {
        return@runSync
      }
      mainHandler.post {
        when (currentWebScreen) {
          WebScreen.HOME -> if (result.updatedCount > 0) {
            renderedHomeSignature = null
            presentHomePage()
          }
          WebScreen.SCORE -> loadScorePageWithLatestData()
          else -> Unit
        }
      }
    }
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
          if (authTimetableCaptureShouldShowCache) {
            showingLiveTimetable = true
            applyWebScreen(WebScreen.TIMETABLE)
          }
          prepareTimetablePage(view, showCachedAfterSuccess = authTimetableCaptureShouldShowCache)
          return
        }

        if (looksLikeLoginUrl(url)) {
          if (!authTimetableCaptureShouldShowCache) {
            authTimetableCaptureShouldShowCache = true
            appendDebugLog("TIMETABLE_SEMESTER", "WARN", "静默刷新课表时跳回登录页，本次自动刷新已取消")
          }
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
          looksLikeTimetableUrl(url) && showingLiveTimetable -> {
            applyWebScreen(WebScreen.TIMETABLE)
            prepareTimetablePage(view, showCachedAfterSuccess = false)
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
    binding.profileTimetableSemesterRow.setOnClickListener {
      startActivity(Intent(this, TimetableSemesterSettingsActivity::class.java))
    }
    binding.profileScoreSyncSettingsRow.setOnClickListener {
      startActivity(Intent(this, ScoreSyncSettingsActivity::class.java))
    }
    binding.profileCheckUpdateRow.setOnClickListener {
      checkForAppUpdate()
    }
    binding.profileOpenLogsRow.setOnClickListener {
      startActivity(Intent(this, LogViewerActivity::class.java))
    }
    binding.profilePresetScoreTestRow.setOnClickListener {
      presetScoreUpdateTestData()
    }
    binding.profileHeadlessScoreTestRow.setOnClickListener {
      runHeadlessScoreTest()
    }
    binding.profileScoreEditorRow.setOnClickListener {
      startActivity(Intent(this, LocalScoreEditorActivity::class.java))
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

  private fun refreshUpdateBadge() {
    val availableVersion = prefs.getString(PREF_UPDATE_AVAILABLE_VERSION, "").orEmpty().trim()
    val showBadge = availableVersion.isNotBlank() && compareVersionNames(availableVersion, currentAppVersionName()) > 0
    binding.profileCheckUpdateBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
  }

  private fun persistAvailableUpdateState(release: AppReleaseInfo?) {
    if (release == null || compareVersionNames(release.versionName, currentAppVersionName()) <= 0) {
      prefs.edit()
        .remove(PREF_UPDATE_AVAILABLE_VERSION)
        .remove(PREF_UPDATE_AVAILABLE_SOURCE)
        .apply()
    } else {
      prefs.edit()
        .putString(PREF_UPDATE_AVAILABLE_VERSION, release.versionName)
        .putString(PREF_UPDATE_AVAILABLE_SOURCE, release.sourceLabel)
        .apply()
    }
    refreshUpdateBadge()
  }

  private fun shouldAutoPromptUpdate(release: AppReleaseInfo): Boolean {
    val promptedVersion = prefs.getString(PREF_UPDATE_PROMPTED_VERSION, "").orEmpty().trim()
    return compareVersionNames(release.versionName, currentAppVersionName()) > 0 && promptedVersion != release.versionName
  }

  private fun markUpdatePrompted(versionName: String) {
    prefs.edit().putString(PREF_UPDATE_PROMPTED_VERSION, versionName).apply()
  }

  private fun checkForAppUpdate(silent: Boolean = false) {
    if (updateCheckInProgress) {
      appendDebugLog("UPDATE", "INFO", "重复触发检查更新，已忽略")
      if (!silent) {
        pendingManualUpdateResult = true
        appendDebugLog("UPDATE", "INFO", "当前正在静默检查更新，完成后将展示本次手动检查结果")
        Toast.makeText(this, "正在检查更新，完成后会自动显示结果", Toast.LENGTH_SHORT).show()
      }
      return
    }

    val currentVersion = currentAppVersionName()
    appendDebugLog("UPDATE", "START", "开始检查更新，当前版本=$currentVersion")
    updateCheckInProgress = true
    if (!silent) {
      updateAppVersionSummary(getString(R.string.profile_update_checking, currentVersion))
      updateStatus("正在检查更新…")
    }

    ioExecutor.execute {
      var giteeError: Throwable? = null
      val release = try {
        enrichReleaseInfo(
          fetchLatestReleaseInfo("Gitee", GITEE_RELEASES_URL, currentVersion),
          currentVersion
        )
      } catch (error: Throwable) {
        giteeError = error
        appendDebugLog("UPDATE", "WARN", "Gitee 检查失败，准备切换 GitHub：${error.message ?: "unknown"}")
        try {
          enrichReleaseInfo(
            fetchLatestReleaseInfo("GitHub", GITHUB_RELEASES_URL, currentVersion),
            currentVersion
          )
        } catch (fallbackError: Throwable) {
          mainHandler.post {
            updateCheckInProgress = false
            if (!silent) {
              updateAppVersionSummary(getString(R.string.profile_update_failed_format, currentVersion))
              showUpdateCheckFailedDialog(giteeError, fallbackError)
            }
          }
          return@execute
        }
      }

      mainHandler.post {
        updateCheckInProgress = false
        val shouldReportResult = !silent || pendingManualUpdateResult
        pendingManualUpdateResult = false
        val latestVersion = release.versionName
        val comparison = compareVersionNames(latestVersion, currentVersion)
        if (comparison > 0) {
          appendDebugLog("UPDATE", "SUCCESS", "发现新版本 $latestVersion，来源=${release.sourceLabel}")
          persistAvailableUpdateState(release)
          updateAppVersionSummary(getString(R.string.profile_update_available_format, currentVersion, latestVersion))
          if (shouldReportResult) {
            markUpdatePrompted(release.versionName)
            showUpdateAvailableDialog(currentVersion, release)
          } else if (silent) {
            if (shouldAutoPromptUpdate(release)) {
              markUpdatePrompted(release.versionName)
              showUpdateAvailableDialog(currentVersion, release)
            }
          }
        } else {
          appendDebugLog("UPDATE", "SUCCESS", "当前已是最新版本，远端版本=$latestVersion")
          persistAvailableUpdateState(null)
          updateAppVersionSummary(getString(R.string.profile_update_latest_format, currentVersion))
          if (shouldReportResult) {
            AlertDialog.Builder(this)
              .setTitle("已是最新版本")
              .setMessage("当前版本 $currentVersion 已是最新版本。")
              .setPositiveButton("知道了", null)
              .show()
          }
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
      val notes = release.releaseNotes.trim()
      if (notes.isNotBlank()) {
        append("\n\n更新简介：\n").append(notes)
      }
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
    showUpdateDownloadDialog(release)

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

  private fun showUpdateDownloadDialog(release: AppReleaseInfo) {
    dismissUpdateDownloadDialog()

    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(12))
    }

    val titleView = TextView(this).apply {
      text = "正在准备下载 ${release.versionName}"
      textSize = 16f
      setTextColor(Color.parseColor("#1F2937"))
      includeFontPadding = false
    }

    val notesView = TextView(this).apply {
      text = release.releaseNotes.trim().ifBlank { "暂无更新简介" }
      textSize = 13f
      setTextColor(Color.parseColor("#6B7380"))
      includeFontPadding = false
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).also { params ->
        params.topMargin = dpToPx(10)
      }
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
    container.addView(notesView)
    container.addView(progressBar)
    container.addView(progressText)

    updateDownloadTitleView = titleView
    updateDownloadNotesView = notesView
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
    updateDownloadNotesView = null
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
  private fun fetchLatestReleaseInfo(
    sourceLabel: String,
    releasesUrl: String,
    currentVersion: String,
    connectTimeoutMs: Int = UPDATE_FETCH_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = UPDATE_FETCH_READ_TIMEOUT_MS
  ): AppReleaseInfo {
    val document = org.jsoup.Jsoup.connect(releasesUrl)
      .userAgent(UPDATE_USER_AGENT)
      .timeout(max(connectTimeoutMs, readTimeoutMs))
      .followRedirects(true)
      .get()

    val anchors = document.select("a[href]")
    val fallbackReleaseAnchor = anchors.firstOrNull { anchor ->
      val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
      href.contains("/releases/tag/", ignoreCase = true) ||
        href.contains("/tag/v", ignoreCase = true) ||
        extractVersionName(anchor.text()) != null
    }
    val fallbackApkUrl = anchors
      .firstOrNull { anchor ->
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        href.contains(".apk", ignoreCase = true)
      }
      ?.absUrl("href")
      ?.takeIf { it.isNotBlank() }

    val releaseEntries = extractReleaseEntries(document, releasesUrl)
    val latestEntry = releaseEntries.maxWithOrNull { left, right ->
      compareVersionNames(left.versionName, right.versionName)
    }
    val versionName = latestEntry?.versionName
      ?: listOf(document.title()).mapNotNull(::extractVersionName).firstOrNull()
      ?: throw IOException("未能从 $sourceLabel 发布页中解析版本号")

    val pageUrl = latestEntry?.pageUrl?.takeIf { it.isNotBlank() }
      ?: fallbackReleaseAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }
      ?: releasesUrl
    val apkUrl = latestEntry?.apkUrl ?: fallbackApkUrl
    val releaseNotes = buildAggregatedReleaseNotes(
      sourceLabel = sourceLabel,
      releaseEntries = releaseEntries,
      currentVersion = currentVersion,
      latestVersion = versionName,
      fallback = extractReleaseNotes(document, null, versionName)
    )

    return AppReleaseInfo(
      sourceLabel = sourceLabel,
      pageUrl = pageUrl,
      versionName = versionName,
      apkUrl = apkUrl,
      releaseNotes = releaseNotes
    )
  }

  private fun extractReleaseEntries(
    document: org.jsoup.nodes.Document,
    releasesUrl: String
  ): List<ParsedReleaseEntry> {
    val anchors = document.select("a[href]")
    val result = mutableListOf<ParsedReleaseEntry>()
    val seenVersions = linkedSetOf<String>()

    anchors.forEach { anchor ->
      val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
      val versionName = listOf(
        anchor.text(),
        anchor.attr("title"),
        href
      ).mapNotNull(::extractVersionName).firstOrNull() ?: return@forEach

      if (!seenVersions.add(versionName)) return@forEach

      val container = sequenceOf(
        anchor.closest("article"),
        anchor.closest("li"),
        anchor.closest("section")
      ).filterNotNull().firstOrNull()

      val pageUrl = href.takeIf { it.isNotBlank() } ?: releasesUrl
      val apkUrl = container
        ?.select("a[href]")
        ?.firstOrNull { item ->
          val itemHref = item.absUrl("href").ifBlank { item.attr("href") }
          itemHref.contains(".apk", ignoreCase = true)
        }
        ?.absUrl("href")
        ?.takeIf { it.isNotBlank() }

      val notesCandidates = buildList {
        if (container != null) {
          addAll(
            listOfNotNull(
              container.selectFirst(".release-body")?.wholeText(),
              container.selectFirst(".markdown-body")?.wholeText(),
              container.selectFirst(".release__description")?.wholeText(),
              container.selectFirst(".release-notes")?.wholeText(),
              container.wholeText()
            )
          )
        }
      }

      val releaseNotes = notesCandidates
        .map { extractReleaseNotesBlock(it, versionName) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

      result += ParsedReleaseEntry(
        sourceLabel = sourceLabelForReleaseUrl(releasesUrl),
        versionName = versionName,
        pageUrl = pageUrl,
        apkUrl = apkUrl,
        releaseNotes = releaseNotes
      )
    }

    return result
  }

  private fun buildAggregatedReleaseNotes(
    sourceLabel: String,
    releaseEntries: List<ParsedReleaseEntry>,
    currentVersion: String,
    latestVersion: String,
    fallback: String
  ): String {
    val matchingEntries = releaseEntries
      .filter { compareVersionNames(it.versionName, currentVersion) > 0 }
      .sortedWith { left, right -> compareVersionNames(right.versionName, left.versionName) }

    val combined = matchingEntries
      .mapNotNull { entry ->
        formatReleaseNotesEntry(entry)
          .takeIf { it.isNotBlank() }
      }
      .joinToString("\n\n")
      .trim()

    if (combined.isNotBlank()) {
      return combined.take(3000)
    }

    return formatReleaseNotesEntry(
      ParsedReleaseEntry(
        sourceLabel = sourceLabel,
        versionName = latestVersion,
        pageUrl = "",
        apkUrl = null,
        releaseNotes = fallback
      )
    )
      .trim()
      .ifBlank {
        if (compareVersionNames(latestVersion, currentVersion) > 0) "暂无更新简介" else ""
      }
      .ifBlank { "暂无更新简介" }
  }

  private fun enrichReleaseInfo(primary: AppReleaseInfo, currentVersion: String): AppReleaseInfo {
    val fallbackSource = if (primary.sourceLabel.equals("Gitee", ignoreCase = true)) {
      "GitHub"
    } else {
      "Gitee"
    }
    val fallbackUrl = if (fallbackSource == "Gitee") GITEE_RELEASES_URL else GITHUB_RELEASES_URL
    val alternate = runCatching {
      fetchLatestReleaseInfo(
        fallbackSource,
        fallbackUrl,
        currentVersion,
        connectTimeoutMs = UPDATE_NOTES_CONNECT_TIMEOUT_MS,
        readTimeoutMs = UPDATE_NOTES_READ_TIMEOUT_MS
      )
    }.onFailure { error ->
      appendDebugLog("UPDATE", "WARN", "补充拉取 $fallbackSource 简介失败：${error.message ?: "unknown"}")
    }.getOrNull()
      ?.takeIf { compareVersionNames(it.versionName, primary.versionName) == 0 }
    val githubApiNotes = if (primary.sourceLabel.equals("GitHub", ignoreCase = true)) {
      runCatching {
        fetchGitHubApiReleaseInfo(
          currentVersion,
          connectTimeoutMs = UPDATE_NOTES_CONNECT_TIMEOUT_MS,
          readTimeoutMs = UPDATE_NOTES_READ_TIMEOUT_MS
        )
      }.onFailure { error ->
        appendDebugLog("UPDATE", "WARN", "拉取 GitHub 多版本简介失败，回退到 GitHub 发布页简介：${error.message ?: "unknown"}")
      }.getOrNull()
        ?.takeIf { compareVersionNames(it.versionName, primary.versionName) == 0 }
        ?.releaseNotes
    } else {
      null
    }
    val resolvedNotes = normalizeReleaseNotes(githubApiNotes ?: primary.releaseNotes)
      .ifBlank { normalizeReleaseNotes(primary.releaseNotes) }
      .ifBlank { "暂无更新简介" }

    return primary.copy(
      pageUrl = primary.pageUrl
        .ifBlank { alternate?.pageUrl.orEmpty() }
        .ifBlank { GITHUB_RELEASES_URL.takeIf { primary.sourceLabel.equals("GitHub", ignoreCase = true) }.orEmpty() },
      apkUrl = primary.apkUrl ?: alternate?.apkUrl,
      releaseNotes = resolvedNotes
    )
  }

  @Throws(IOException::class)
  private fun fetchGitHubApiReleaseInfo(
    currentVersion: String,
    connectTimeoutMs: Int = UPDATE_FETCH_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = UPDATE_FETCH_READ_TIMEOUT_MS
  ): AppReleaseInfo {
    val releaseEntries = fetchGitHubApiReleaseEntries(connectTimeoutMs, readTimeoutMs)
    val latestEntry = releaseEntries.maxWithOrNull { left, right ->
      compareVersionNames(left.versionName, right.versionName)
    } ?: throw IOException("GitHub API 未返回可用版本")

    return AppReleaseInfo(
      sourceLabel = "GitHub",
      pageUrl = latestEntry.pageUrl.ifBlank { GITHUB_RELEASES_URL },
      versionName = latestEntry.versionName,
      apkUrl = latestEntry.apkUrl,
      releaseNotes = buildAggregatedReleaseNotes(
        sourceLabel = "GitHub",
        releaseEntries = releaseEntries,
        currentVersion = currentVersion,
        latestVersion = latestEntry.versionName,
        fallback = latestEntry.releaseNotes
      )
    )
  }

  @Throws(IOException::class)
  private fun fetchGitHubApiReleaseEntries(
    connectTimeoutMs: Int,
    readTimeoutMs: Int
  ): List<ParsedReleaseEntry> {
    val payload = fetchTextFromUrl(
      url = GITHUB_RELEASES_API_URL,
      accept = "application/vnd.github+json",
      connectTimeoutMs = connectTimeoutMs,
      readTimeoutMs = readTimeoutMs
    )
    val array = JSONArray(payload)
    val result = mutableListOf<ParsedReleaseEntry>()

    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      if (item.optBoolean("draft")) continue

      val versionName = listOf(
        item.optString("tag_name"),
        item.optString("name"),
        item.optString("html_url")
      ).mapNotNull(::extractVersionName).firstOrNull() ?: continue

      val pageUrl = item.optString("html_url").ifBlank { GITHUB_RELEASES_URL }
      val body = item.optString("body")
      val assets = item.optJSONArray("assets")
      val apkUrl = findApkUrlInReleaseAssets(assets)
      val releaseNotes = extractReleaseNotesBlock(body, versionName)
        .ifBlank { normalizeReleaseNotes(body) }

      result += ParsedReleaseEntry(
        sourceLabel = "GitHub",
        versionName = versionName,
        pageUrl = pageUrl,
        apkUrl = apkUrl,
        releaseNotes = releaseNotes
      )
    }

    return result
  }

  private fun findApkUrlInReleaseAssets(assets: JSONArray?): String? {
    if (assets == null) return null
    for (index in 0 until assets.length()) {
      val asset = assets.optJSONObject(index) ?: continue
      val url = asset.optString("browser_download_url")
        .ifBlank { asset.optString("url") }
        .takeIf { it.isNotBlank() }
      if (url != null && url.contains(".apk", ignoreCase = true)) {
        return url
      }
    }
    return null
  }

  @Throws(IOException::class)
  private fun fetchTextFromUrl(
    url: String,
    accept: String = "*/*",
    connectTimeoutMs: Int = UPDATE_FETCH_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = UPDATE_FETCH_READ_TIMEOUT_MS
  ): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      instanceFollowRedirects = true
      connectTimeout = connectTimeoutMs
      readTimeout = readTimeoutMs
      setRequestProperty("User-Agent", UPDATE_USER_AGENT)
      setRequestProperty("Accept", accept)
    }

    try {
      connection.connect()
      if (connection.responseCode !in 200..299) {
        throw IOException("HTTP ${connection.responseCode}")
      }
      return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  private fun selectBestReleaseNotes(vararg candidates: String): String {
    val normalizedCandidates = candidates
      .map(::normalizeReleaseNotes)
      .filter { it.isNotBlank() && it != "暂无更新简介" }
    if (normalizedCandidates.isEmpty()) {
      return "暂无更新简介"
    }
    return normalizedCandidates.maxByOrNull(::releaseNotesScore) ?: "暂无更新简介"
  }

  private fun formatReleaseNotesEntry(entry: ParsedReleaseEntry): String {
    val notes = normalizeReleaseNotes(entry.releaseNotes)
    if (notes.isBlank() || notes == "暂无更新简介") {
      return ""
    }
    return buildString {
      append("v")
      append(entry.versionName)
      append(" · ")
      append(entry.sourceLabel)
      append('\n')
      append(notes)
    }.trim()
  }

  private fun normalizeReleaseNotes(notes: String): String {
    return notes
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .trim()
  }

  private fun sourceLabelForReleaseUrl(releasesUrl: String): String {
    return if (releasesUrl.contains("gitee.com", ignoreCase = true)) "Gitee" else "GitHub"
  }

  private fun releaseNotesScore(notes: String): Int {
    val normalized = normalizeReleaseNotes(notes)
    if (normalized.isBlank() || normalized == "暂无更新简介") {
      return 0
    }
    val headingCount = Regex("""(?m)^##""").findAll(normalized).count()
    return headingCount * 1000 + normalized.length
  }

  private fun extractReleaseNotes(
    document: org.jsoup.nodes.Document,
    releaseAnchor: org.jsoup.nodes.Element?,
    versionName: String
  ): String {
    val directCandidates = listOfNotNull(
      document.selectFirst(".release-body"),
      document.selectFirst(".markdown-body"),
      document.selectFirst(".release__description"),
      document.selectFirst(".release-notes"),
      document.selectFirst("article .markdown-body"),
      releaseAnchor?.closest("article")?.selectFirst(".markdown-body"),
      releaseAnchor?.closest("li")?.selectFirst(".markdown-body")
    ).map { it.wholeText() }

    val best = buildList {
      addAll(directCandidates)
      add(document.select("article").firstOrNull()?.wholeText().orEmpty())
      add(document.body().wholeText())
    }.map { candidate ->
      extractReleaseNotesBlock(candidate, versionName)
    }.firstOrNull { it.isNotBlank() }.orEmpty()

    if (best.isBlank()) return "暂无更新简介"
    return best
      .lineSequence()
      .map(String::trim)
      .filter { it.isNotBlank() }
      .joinToString("\n")
      .take(1200)
      .trim()
      .ifBlank { "暂无更新简介" }
  }

  private fun extractReleaseNotesBlock(raw: String, versionName: String): String {
    val normalized = raw
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace(versionName, "")
    val start = normalized.indexOf("##")
    if (start < 0) return ""
    val tail = normalized.substring(start)
    val endCandidates = listOf(
      tail.indexOf("\n下载"),
      tail.indexOf("\nAssets"),
      tail.indexOf("\nassets"),
      tail.indexOf("\r下载"),
      tail.indexOf("\rAssets"),
      tail.indexOf("\rassets")
    ).filter { it > 0 }
    val end = endCandidates.minOrNull() ?: tail.length
    return tail.substring(0, end).trim()
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
      fetchLatestReleaseInfo(fallbackSource, fallbackUrl, currentAppVersionName())
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
    if (forceReload) {
      clearSessionCookies()
    }
    loadLoginPageInWebView()
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

  private fun loadLoginPageInWebView() {
    updateStatus(getString(R.string.status_loading_login))
    binding.authWebView.loadUrl(LOGIN_URL)
  }

  private fun clearSessionCookies() {
    CookieManager.getInstance().removeSessionCookies(null)
    CookieManager.getInstance().flush()
  }

  private fun resolveCurrentCredentials(): Pair<String, String>? {
    val username = binding.usernameInput.editText?.text?.toString().orEmpty().trim()
      .ifBlank { prefs.getString(PREF_USERNAME, "").orEmpty().trim() }
    val password = binding.passwordInput.editText?.text?.toString().orEmpty().trim()
      .ifBlank { prefs.getString(PREF_PASSWORD, "").orEmpty().trim() }
    return if (username.isNotBlank() && password.isNotBlank()) {
      username to password
    } else {
      null
    }
  }

  private fun recognizeCaptchaTextSync(bitmap: Bitmap): String? {
    val result = AtomicReference<String?>()
    val error = AtomicReference<Throwable?>()
    val latch = CountDownLatch(1)
    val processedBitmap = preprocessCaptcha(bitmap)
    val image = InputImage.fromBitmap(processedBitmap, 0)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
      .addOnSuccessListener { visionText ->
        result.set(visionText.text.replace(Regex("[^a-zA-Z0-9]"), ""))
        latch.countDown()
      }
      .addOnFailureListener { throwable ->
        error.set(throwable)
        latch.countDown()
      }

    if (!latch.await(12, TimeUnit.SECONDS)) {
      throw IllegalStateException("验证码识别超时")
    }
    error.get()?.let { throw IllegalStateException(it.message ?: "验证码识别失败", it) }
    return result.get()
  }

  private fun runHeadlessScoreTest() {
    val credentials = resolveCurrentCredentials()
    if (credentials == null) {
      Toast.makeText(this, "请先在个人中心填写账号密码", Toast.LENGTH_SHORT).show()
      updateStatus(getString(R.string.status_headless_score_test_missing_credentials))
      return
    }
    if (headlessLoginInProgress) {
      Toast.makeText(this, "纯 HTTP 测试正在进行中", Toast.LENGTH_SHORT).show()
      return
    }

    val (username, password) = credentials
    headlessLoginInProgress = true
    persistCredentials(username, password)
    updateStatus(getString(R.string.status_headless_score_test_start))
    appendDebugLog("HEADLESS_SCORE_TEST", "START", "开始纯 HTTP 成绩测试，用户=$username")

    ioExecutor.execute {
      try {
        val loginResult = HeadlessLoginClient(::appendDebugLog).login(
          loginUrl = LOGIN_URL,
          timetableUrl = TIMETABLE_URL,
          username = username,
          password = password,
          recognizeCaptcha = ::recognizeCaptchaTextSync
        )
        val scores = fetchScoreRecordsWithCookies(loginResult.cookies)
        File(filesDir, HEADLESS_SCORE_TEST_FILE).writeText(scores.toString(), Charsets.UTF_8)
        appendDebugLog(
          "HEADLESS_SCORE_TEST",
          "SUCCESS",
          "纯 HTTP 成绩测试成功，共 ${scores.length()} 条，结果已写入 $HEADLESS_SCORE_TEST_FILE"
        )
        mainHandler.post {
          headlessLoginInProgress = false
          updateStatus(getString(R.string.status_headless_score_test_success, scores.length()))
          Toast.makeText(this, "纯 HTTP 成绩测试成功，共 ${scores.length()} 条", Toast.LENGTH_LONG).show()
        }
      } catch (error: Exception) {
        appendDebugLog("HEADLESS_SCORE_TEST", "FAIL", error.message ?: "unknown")
        mainHandler.post {
          headlessLoginInProgress = false
          updateStatus(getString(R.string.status_headless_score_test_failed, error.message ?: "unknown"))
          Toast.makeText(this, "纯 HTTP 成绩测试失败", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun fetchScoreRecordsWithCookies(cookies: Map<String, String>): JSONArray {
    appendDebugLog("HEADLESS_SCORE_TEST", "INFO", "开始独立拉取成绩页")
    val connection = URL(SCORE_LIST_URL).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      connection.useCaches = false
      connection.instanceFollowRedirects = true
      connection.connectTimeout = 10000
      connection.readTimeout = 10000
      connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36")
      connection.setRequestProperty("Referer", TIMETABLE_URL)
      if (cookies.isNotEmpty()) {
        connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
      }
      val responseCode = connection.responseCode
      appendDebugLog("HEADLESS_SCORE_TEST", if (responseCode in 200..299) "INFO" else "WARN", "成绩测试响应码=$responseCode")
      val bytes = (if (responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream)
        .use { it.readBytes() }
      val document = org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, SCORE_LIST_URL)
      appendDebugLog("HEADLESS_SCORE_TEST", "INFO", "成绩测试页面标题=${document.title().ifBlank { "-" }}")
      return parseScoreDocument(document)
    } finally {
      connection.disconnect()
    }
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
    binding.homeOpenScoreButton.setOnClickListener { showCachedScorePage() }
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

  private fun prepareTimetablePage(
    webView: WebView,
    showCachedAfterSuccess: Boolean
  ) {
    maybeSwitchTimetableSemesterOnWebView(webView) {
      captureTimetablePage(webView, showCachedAfterSuccess = showCachedAfterSuccess)
    }
  }

  private fun maybeSwitchTimetableSemesterOnWebView(
    webView: WebView,
    onReady: () -> Unit
  ) {
    val desiredSemester = TimetableSemesterStore.resolveDesiredSemester(this)
    val script = """
      (function() {
        const desired = ${JSONObject.quote(desiredSemester)};
        const select = document.querySelector("select[name='xnxq01id']") || document.getElementById("xnxq01id");
        const weekSelect = document.querySelector("select[name='zc']") || document.getElementById("zc");
        if (!select) {
          return JSON.stringify({ availableSemesters: [], currentSemester: "", desiredSemester: desired, switched: false, weekFilter: "" });
        }
        const availableSemesters = Array.from(select.options || [])
          .map((option) => String(option.value || option.textContent || "").trim())
          .filter((value) => value.length > 0);
        const currentSemester = String(
          select.value ||
          (select.selectedOptions && select.selectedOptions[0] ? select.selectedOptions[0].value : "") ||
          availableSemesters[0] ||
          ""
        ).trim();
        const weekFilter = String(
          weekSelect ? (weekSelect.value || "") : ""
        ).trim();
        if (desired && availableSemesters.includes(desired) && currentSemester !== desired) {
          Array.from(select.options || []).forEach((option) => {
            option.selected = String(option.value || option.textContent || "").trim() === desired;
          });
          select.value = desired;
          if (weekSelect) {
            weekSelect.value = "";
          }
          const form = select.form || document.forms.Form1 || document.forms[0];
          if (typeof select.onchange === "function") {
            window.setTimeout(() => select.onchange(), 0);
            return JSON.stringify({
              availableSemesters,
              currentSemester,
              desiredSemester: desired,
              switched: true,
              weekFilter
            });
          }
          if (form && typeof form.submit === "function") {
            window.setTimeout(() => form.submit(), 0);
            return JSON.stringify({
              availableSemesters,
              currentSemester,
              desiredSemester: desired,
              switched: true,
              weekFilter
            });
          }
        }
        return JSON.stringify({
          availableSemesters,
          currentSemester,
          desiredSemester: desired,
          switched: false,
          weekFilter
        });
      })();
    """.trimIndent()

    webView.evaluateJavascript(script) { rawValue ->
      val snapshot = parseTimetableSemesterSnapshot(decodeJsValue(rawValue))
      if (snapshot != null && snapshot.availableSemesters.isNotEmpty()) {
        TimetableSemesterStore.updateCatalog(
          this,
          snapshot.availableSemesters,
          snapshot.currentSemester
        )
        appendDebugLog(
          "TIMETABLE",
          "INFO",
          "网页课表学期：current=${snapshot.currentSemester.ifBlank { "-" }}, desired=${snapshot.desiredSemester.ifBlank { "-" }}, week=${snapshot.weekFilter.ifBlank { "(全部)" }}, options=${snapshot.availableSemesters.size}"
        )
      }
      if (snapshot?.switched == true) {
        appendDebugLog(
          "TIMETABLE",
          "INFO",
          "课表网页已从 ${snapshot.currentSemester} 切换到 ${snapshot.desiredSemester}，并清空周次筛选"
        )
        updateStatus("已切换到学期 ${snapshot.desiredSemester}，正在重新加载课表…")
        return@evaluateJavascript
      }
      onReady()
    }
  }

  private fun parseTimetableSemesterSnapshot(rawValue: String): TimetableSemesterSnapshot? {
    if (rawValue.isBlank()) return null
    return runCatching {
      val payload = JSONObject(rawValue)
      TimetableSemesterSnapshot(
        availableSemesters = buildList {
          val array = payload.optJSONArray("availableSemesters") ?: JSONArray()
          for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) add(value)
          }
        },
        currentSemester = payload.optString("currentSemester").trim(),
        desiredSemester = payload.optString("desiredSemester").trim(),
        switched = payload.optBoolean("switched"),
        weekFilter = payload.optString("weekFilter").trim()
      )
    }.getOrNull()
  }

  private fun refreshGeneratedCacheAfterStartup() {
    TimetableSemesterStore.refreshCatalogFromRawHtmlIfNeeded(this)
    migrateTimetableCacheParserIfNeeded()
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
      val pendingUpdates = readScoreUpdateItems()
      val pendingFingerprints = pendingUpdates.map { it.optString("fingerprint") }.filter { it.isNotBlank() }.toSet()
      val preparedScores = markPendingScoreUpdatesInJson(latestJson, pendingFingerprints)
      val pendingUiIds = extractPendingScoreUiIds(preparedScores)
      injectScoreJsonIntoTemplate(templateHtml, preparedScores, pendingUiIds)
    }

    if (hasPendingScoreUpdates()) {
      appendDebugLog("SCORE_UPDATE", "INFO", "进入成绩页，准备清除主页提示")
      consumePendingScoreUpdates()
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

  private fun injectScoreJsonIntoTemplate(
    templateHtml: String,
    scoresJson: String,
    pendingUiIds: List<String> = emptyList()
  ): String {
    val pattern = Regex("""const rawScores = .*?;""", setOf(RegexOption.DOT_MATCHES_ALL))
    val html = if (pattern.containsMatchIn(templateHtml)) {
      templateHtml.replace(pattern, "const rawScores = ${serializeForScript(scoresJson)};")
    } else {
      templateHtml
    }
    return injectScoreRowFlashHelper(html, pendingUiIds)
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
    val scoreUpdateMetaFile = File(filesDir, SCORE_UPDATE_META_FILE)
    val homePart = if (homeCacheFile.exists()) "${homeCacheFile.length()}:${homeCacheFile.lastModified()}" else "missing"
    val jsonPart = if (cacheJsonFile.exists()) "${cacheJsonFile.length()}:${cacheJsonFile.lastModified()}" else "missing"
    val examPart = if (examJsonFile.exists()) "${examJsonFile.length()}:${examJsonFile.lastModified()}" else "missing"
    val scorePart = if (scoreJsonFile.exists()) "${scoreJsonFile.length()}:${scoreJsonFile.lastModified()}" else "missing"
    val scoreUpdatePart = if (scoreUpdateMetaFile.exists()) "${scoreUpdateMetaFile.length()}:${scoreUpdateMetaFile.lastModified()}" else "missing"
    return listOf(currentAssetExportId ?: "no-export", homePart, jsonPart, examPart, scorePart, scoreUpdatePart).joinToString("|")
  }

  private fun renderNativeHome() {
    currentHomeImages = loadHomeImages()
    currentHomeImageIndex = currentHomeImageIndex.coerceIn(0, max(0, currentHomeImages.lastIndex))
    setHomeImageIndex(currentHomeImageIndex)
    renderHomeMenu()

    val scoreUpdates = loadPendingScoreUpdates()
    binding.homeRecentScoreCard.visibility = if (scoreUpdates.isEmpty()) View.GONE else View.VISIBLE
    if (scoreUpdates.isNotEmpty()) {
      binding.homeRecentScoreHint.text = getString(R.string.home_recent_score_updated)
      renderRecentScoreUpdates(scoreUpdates)
    } else {
      binding.homeRecentScoreHint.text = getString(R.string.home_recent_score_empty)
      binding.homeRecentScoreList.removeAllViews()
    }

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
    val hasScoreUpdateDot = hasPendingScoreUpdates()
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

      val iconContainer = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
      }

      val icon = ImageView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        setImageResource(item.iconRes)
        background = null
        alpha = 1f
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
      }
      iconContainer.addView(icon)

      if (item.key == "score" && hasScoreUpdateDot) {
        iconContainer.addView(View(this).apply {
          layoutParams = FrameLayout.LayoutParams(dpToPx(10), dpToPx(10), android.view.Gravity.END or android.view.Gravity.TOP).also {
            it.topMargin = dpToPx(2)
            it.marginEnd = dpToPx(2)
          }
          background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#EA5B5B"))
          }
        })
      }

      val label = TextView(this).apply {
        text = item.label
        textSize = 14f
        setTextColor(Color.parseColor("#6B7380"))
        gravity = android.view.Gravity.CENTER
        minLines = 2
        includeFontPadding = false
      }

      itemView.addView(iconContainer)
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

  private fun loadPendingScoreUpdates(): List<HomeRecentScoreEntry> {
    if (!hasPendingScoreUpdates()) return emptyList()
    return readScoreUpdateItems()
      .take(3)
      .map { item ->
        val semester = item.optString("semester").ifBlank { "成绩更新" }
        val score = item.optString("score").ifBlank { "--" }
        val flag = listOf(item.optString("courseNature"), item.optString("courseAttribute"))
          .firstOrNull { it.isNotBlank() }
          .orEmpty()
        HomeRecentScoreEntry(
          displayTag = "新",
          title = item.optString("courseName").ifBlank { "未命名课程" },
          meta = listOf(semester, flag).filter { it.isNotBlank() }.joinToString(" · "),
          score = score,
          isAlert = true
        )
      }
  }

  private fun renderRecentScoreUpdates(items: List<HomeRecentScoreEntry>) {
    binding.homeRecentScoreList.removeAllViews()
    items.forEachIndexed { index, item ->
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dpToPx(12), 0, dpToPx(12))
      }

      val badge = TextView(this).apply {
        text = item.displayTag
        gravity = android.view.Gravity.CENTER
        textSize = 15f
        layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(52))
        setTextColor(Color.parseColor("#7D8798"))
        background = android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.OVAL
          setColor(Color.parseColor("#E4ECFB"))
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

      val score = TextView(this).apply {
        text = item.score
        textSize = 16f
        setTextColor(Color.parseColor("#8F959C"))
      }

      row.addView(badge)
      row.addView(middle)
      row.addView(score)
      binding.homeRecentScoreList.addView(row)
      if (index < items.lastIndex) {
        binding.homeRecentScoreList.addView(View(this).apply {
          layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(1)
          )
          setBackgroundColor(Color.parseColor("#33B0BCCB"))
        })
      }
    }
  }

  private fun buildRecentCourses(courses: List<TimetableCourse>): List<HomeRecentEntry> {
    if (courses.isEmpty()) return emptyList()
    val renderedSemester = TimetableSemesterStore.resolveRenderedSemester(this)
    val calendar = TimetableSemesterStore.resolveCalendar(renderedSemester)
    if (calendar.week1Monday == null) return emptyList()
    val normalized = courses.map { course ->
      val match = Regex("""(\d+)(?:-(\d+))?""").find(course.periods)
      NormalizedCourse(
        course = course,
        startPeriod = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        endPeriod = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        weeks = parseWeeks(course.weeks)
      )
    }
    val allWeeks = normalized.flatMap { it.weeks }.distinct().sorted()
    if (TimetableSemesterStore.shouldPreferFullTimetable(renderedSemester, allWeeks)) {
      return emptyList()
    }
    val today = LocalDate.now()
    val now = LocalTime.now()
    val result = mutableListOf<HomeRecentEntry>()

    for (offset in 0..1) {
      val date = today.plusDays(offset.toLong())
      val weekday = HOME_WEEKDAYS[(date.dayOfWeek.value - 1) % HOME_WEEKDAYS.size]
      val week = TimetableSemesterStore.weekForDate(calendar, date) ?: continue

      normalized
        .filter { it.course.weekday == weekday && it.weeks.contains(week) }
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

  private fun captureTimetablePage(
    webView: WebView,
    showCachedAfterSuccess: Boolean
  ) {
    if (cacheCaptureInProgress) {
      return
    }

    cacheCaptureInProgress = true
    updateStatus("已进入课表页，正在抓取并更新本地缓存…")

    webView.evaluateJavascript(
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
        handleCapturedTimetableHtml(html, showCachedAfterSuccess = showCachedAfterSuccess)
      }
    }
  }

  private fun handleCapturedTimetableHtml(
    html: String,
    successStatus: String? = null,
    showCachedAfterSuccess: Boolean = true
  ) {
    try {
      appendDebugLog(
        "TIMETABLE_CAPTURE",
        "START",
        "开始处理课表 HTML，length=${html.length}，showCachedAfterSuccess=$showCachedAfterSuccess"
      )
      TimetableSemesterStore.updateFromTimetableHtml(this@MainActivity, html)
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "课表学期目录已从 HTML 刷新")
      val courses = TimetableParser.parse(html)
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "课表解析完成，courses=${courses.size}")
      val renderedHomeHtml = TimetableRenderer.toHomeHtml(this@MainActivity, courses)
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "首页课表 HTML 已生成，length=${renderedHomeHtml.length}")
      val renderedHtml = TimetableRenderer.toHtml(this@MainActivity, courses)
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "完整课表 HTML 已生成，length=${renderedHtml.length}")
      val json = TimetableRenderer.toJson(courses)
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "课表 JSON 已生成，length=${json.length}")

      File(filesDir, GENERATED_HOME_HTML_FILE).writeText(renderedHomeHtml, Charsets.UTF_8)
      File(filesDir, GENERATED_CACHE_HTML_FILE).writeText(renderedHtml, Charsets.UTF_8)
      File(filesDir, CACHE_JSON_FILE).writeText(json, Charsets.UTF_8)
      File(filesDir, CACHE_RAW_HTML_FILE).writeText(html, Charsets.UTF_8)
      prefs.edit()
        .putInt(PREF_TIMETABLE_CACHE_PARSER_VERSION, CURRENT_TIMETABLE_CACHE_PARSER_VERSION)
        .apply()
      appendDebugLog("TIMETABLE_CAPTURE", "INFO", "课表缓存文件写入完成")
      val examSyncResult = runCatching { syncExamCacheFromSession(courses) }
        .onFailure { appendDebugLog("EXAM", "FAIL", it.message ?: "unknown") }
      val scoreSyncResult = runCatching { syncScoreCacheFromSession() }
        .onFailure { appendDebugLog("SCORE", "FAIL", it.message ?: "unknown") }

      mainHandler.post {
        cacheCaptureInProgress = false
        renderedHomeSignature = null
        val examCount = examSyncResult.getOrNull()
        val scoreCount = scoreSyncResult.getOrNull()
        when {
          successStatus != null -> updateStatus(successStatus)
          examCount != null && scoreCount != null ->
            updateStatus("本地缓存已更新，共解析 ${courses.size} 条课程，${examCount} 场考试，${scoreCount} 条成绩。")
          examCount != null ->
            updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程，${examCount} 场考试；成绩同步失败。")
          scoreCount != null ->
            updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程，${scoreCount} 条成绩；考试安排同步失败。")
          else ->
            updateStatus("课表缓存已更新，共解析 ${courses.size} 条课程；考试安排和成绩同步失败。")
        }
        CourseNotificationScheduler.sync(this@MainActivity)
        ExamOngoingNotificationScheduler.sync(this@MainActivity)
        if (isAutoUpdating) {
          val msg = if (autoUpdateFailedAttempts == 0) "更新成功 (1次通过)" else "更新成功 (失败 ${autoUpdateFailedAttempts} 次后)"
          Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
          isAutoUpdating = false
        }
        if (showCachedAfterSuccess) {
          showCachedTimetable()
        } else {
          authTimetableCaptureShouldShowCache = true
          refreshGeneratedCacheAfterStartup()
        }
      }
    } catch (error: Throwable) {
      Log.e("ClassSche", "Failed to handle captured timetable html", error)
      appendDebugLog(
        "TIMETABLE_CAPTURE",
        "FAIL",
        throwableSummary(error)
      )
      mainHandler.post {
        cacheCaptureInProgress = false
        authTimetableCaptureShouldShowCache = true
        updateStatus("缓存同步失败：${error.message ?: "unknown"}")
        if (isAutoUpdating) {
          isAutoUpdating = false
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
    val previousScores = readScoreArrayFromFile(File(filesDir, SCORE_JSON_FILE))
    val scores = fetchScoreRecords()
    File(filesDir, SCORE_JSON_FILE).writeText(scores.toString(), Charsets.UTF_8)
    val updatedItems = detectUpdatedScoreItems(previousScores, scores)
    val hasNewUpdates = previousScores.length() > 0 && updatedItems.isNotEmpty()
    val pendingItems = persistScoreUpdateMeta(hasNewUpdates, updatedItems)
    if (hasNewUpdates) {
      appendDebugLog("SCORE_UPDATE", "SUCCESS", "检测到 ${updatedItems.size} 条成绩更新")
      showScoreUpdateNotification(updatedItems)
    } else if (pendingItems.isNotEmpty()) {
      appendDebugLog("SCORE_UPDATE", "INFO", "本次没有新增成绩变动，保留 ${pendingItems.size} 条未读更新")
    } else {
      appendDebugLog("SCORE_UPDATE", "INFO", "本次未检测到新的成绩变动")
    }
    appendDebugLog("SCORE", "SUCCESS", "成绩缓存写入完成，共 ${scores.length()} 条")
    return scores.length()
  }

  private fun readScoreArrayFromFile(file: File): JSONArray {
    if (!file.exists() || file.length() <= 0L) return JSONArray()
    return runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
  }

  private fun detectUpdatedScoreItems(previousScores: JSONArray, latestScores: JSONArray): List<JSONObject> {
    if (previousScores.length() <= 0 || latestScores.length() <= 0) return emptyList()
    val previousFingerprints = buildSet {
      for (index in 0 until previousScores.length()) {
        val item = previousScores.optJSONObject(index) ?: continue
        add(scoreFingerprint(item))
      }
    }
    val updates = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    for (index in 0 until latestScores.length()) {
      val item = latestScores.optJSONObject(index) ?: continue
      val fingerprint = scoreFingerprint(item)
      if (fingerprint in previousFingerprints || !seen.add(fingerprint)) {
        continue
      }
      updates += buildScoreUpdateItem(item)
    }
    return updates
  }

  private fun scoreFingerprint(item: JSONObject): String {
    return listOf(
      item.optString("semester"),
      item.optString("courseCode"),
      item.optString("courseName"),
      item.optString("score"),
      item.optString("scoreIdentifier"),
      item.optString("credits"),
      item.optString("courseAttribute"),
      item.optString("courseNature")
    ).joinToString("|") { normalizeScoreMetaText(it) }
  }

  private fun normalizeScoreMetaText(value: String?): String =
    value.orEmpty().replace(Regex("""\s+"""), "").trim()

  private fun persistScoreUpdateMeta(pending: Boolean, updatedItems: List<JSONObject>): List<JSONObject> {
    val file = File(filesDir, SCORE_UPDATE_META_FILE)
    val currentMeta = readScoreUpdateMeta()
    val existingPendingItems = if (currentMeta.optBoolean("pending")) {
      readScoreUpdateItems(currentMeta)
    } else {
      emptyList()
    }
    val nextPendingItems = when {
      pending && updatedItems.isNotEmpty() -> mergePendingScoreUpdateItems(existingPendingItems, updatedItems)
      existingPendingItems.isNotEmpty() -> existingPendingItems
      pending -> updatedItems
      else -> emptyList()
    }
    val payload = JSONObject().apply {
      put("pending", nextPendingItems.isNotEmpty())
      put(
        "updatedAt",
        when {
          nextPendingItems.isEmpty() -> System.currentTimeMillis()
          pending && updatedItems.isNotEmpty() -> System.currentTimeMillis()
          currentMeta.optLong("updatedAt") > 0L -> currentMeta.optLong("updatedAt")
          else -> System.currentTimeMillis()
        }
      )
      put("items", JSONArray().apply { nextPendingItems.forEach(::put) })
    }
    file.writeText(payload.toString(), Charsets.UTF_8)
    return nextPendingItems
  }

  private fun readScoreUpdateMeta(): JSONObject {
    val file = File(filesDir, SCORE_UPDATE_META_FILE)
    if (!file.exists() || file.length() <= 0L) {
      return JSONObject().apply {
        put("pending", false)
        put("items", JSONArray())
      }
    }
    return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse {
      JSONObject().apply {
        put("pending", false)
        put("items", JSONArray())
      }
    }
  }

  private fun hasPendingScoreUpdates(): Boolean {
    val meta = readScoreUpdateMeta()
    return meta.optBoolean("pending") && meta.optJSONArray("items")?.length()?.let { it > 0 } == true
  }

  private fun readScoreUpdateItems(): List<JSONObject> {
    return readScoreUpdateItems(readScoreUpdateMeta())
  }

  private fun readScoreUpdateItems(meta: JSONObject): List<JSONObject> {
    val array = meta.optJSONArray("items") ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(item)
      }
    }
  }

  private fun mergePendingScoreUpdateItems(
    existingItems: List<JSONObject>,
    newItems: List<JSONObject>
  ): List<JSONObject> {
    val merged = LinkedHashMap<String, JSONObject>()
    existingItems.forEach { item ->
      val fingerprint = item.optString("fingerprint").ifBlank { scoreFingerprint(item) }
      if (fingerprint.isNotBlank()) {
        merged[fingerprint] = item
      }
    }
    newItems.forEach { item ->
      val fingerprint = item.optString("fingerprint").ifBlank { scoreFingerprint(item) }
      if (fingerprint.isNotBlank()) {
        merged[fingerprint] = item
      }
    }
    return merged.values.toList()
  }

  private fun markPendingScoreUpdatesInJson(scoresJson: String, pendingFingerprints: Set<String>): String {
    if (pendingFingerprints.isEmpty()) return scoresJson
    val array = runCatching { JSONArray(scoresJson) }.getOrNull() ?: return scoresJson
    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      item.put("isNew", scoreFingerprint(item) in pendingFingerprints)
    }
    return array.toString()
  }

  private fun extractPendingScoreUiIds(scoresJson: String): List<String> {
    val array = runCatching { JSONArray(scoresJson) }.getOrNull() ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        if (item.optBoolean("isNew")) {
          add("score-$index")
        }
      }
    }
  }

  private fun injectScoreRowFlashHelper(templateHtml: String, pendingUiIds: List<String>): String {
    if (pendingUiIds.isEmpty()) return templateHtml
    val helper = """
      <style>
        @keyframes classscheScoreFlash {
          0%, 100% { box-shadow: inset 0 0 0 0 rgba(255, 211, 105, 0); }
          30% { box-shadow: inset 0 0 0 999px rgba(255, 230, 160, 0.72); }
          60% { box-shadow: inset 0 0 0 999px rgba(255, 242, 201, 0.22); }
        }
        .score-row.fresh-highlight {
          animation: classscheScoreFlash 540ms ease-in-out 2;
        }
      </style>
      <script>
        (function() {
          const pendingIds = ${serializeForScript(JSONArray(pendingUiIds).toString())};
          const applyHighlight = function() {
            pendingIds.forEach(function(id) {
              const row = document.querySelector('[data-score-id="' + id + '"]');
              if (row instanceof HTMLElement) {
                row.classList.add('fresh-highlight');
              }
            });
          };
          const run = function() { window.setTimeout(applyHighlight, 80); };
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', run, { once: true });
          } else {
            run();
          }
        })();
      </script>
    """.trimIndent()
    return if (templateHtml.contains("</body>", ignoreCase = true)) {
      templateHtml.replace("</body>", "$helper\n</body>")
    } else {
      templateHtml + helper
    }
  }

  private fun consumePendingScoreUpdates() {
    val meta = readScoreUpdateMeta()
    if (!meta.optBoolean("pending")) return
    meta.put("pending", false)
    meta.put("items", JSONArray())
    File(filesDir, SCORE_UPDATE_META_FILE).writeText(meta.toString(), Charsets.UTF_8)
    cancelScoreUpdateNotification()
    renderedHomeSignature = null
  }

  private fun presetScoreUpdateTestData() {
    val existingScores = loadLatestScoreArray()
    val sourceScores = if (existingScores.length() > 0) {
      existingScores
    } else {
      buildSampleScoreArray().also {
        File(filesDir, SCORE_JSON_FILE).writeText(it.toString(), Charsets.UTF_8)
        appendDebugLog("SCORE_TEST", "INFO", "本地无成绩缓存，已写入通用示例成绩数据")
      }
    }
    val pendingItems = buildPendingScoreUpdateItemsFromArray(sourceScores, limit = 2)
    if (pendingItems.isEmpty()) {
      Toast.makeText(this, "没有可用于预置的成绩数据", Toast.LENGTH_SHORT).show()
      appendDebugLog("SCORE_TEST", "WARN", "预置成绩测试数据失败：没有可用成绩项")
      return
    }
    persistScoreUpdateMeta(true, pendingItems)
    renderedHomeSignature = null
    appendDebugLog("SCORE_TEST", "SUCCESS", "已预置 ${pendingItems.size} 条成绩测试更新")
    showScoreUpdateNotification(pendingItems)
    if (currentWebScreen == WebScreen.HOME) {
      presentHomePage()
    }
    Toast.makeText(this, "已预置成绩测试数据，可去首页查看红点和更新卡片", Toast.LENGTH_LONG).show()
  }

  private fun loadLatestScoreArray(): JSONArray {
    val latestJson = readLatestScoreJson()?.trim().orEmpty()
    if (latestJson.isBlank()) return JSONArray()
    return runCatching { JSONArray(latestJson) }.getOrElse { JSONArray() }
  }

  private fun buildPendingScoreUpdateItemsFromArray(array: JSONArray, limit: Int): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    for (index in 0 until array.length()) {
      if (result.size >= limit) break
      val item = array.optJSONObject(index) ?: continue
      val courseName = item.optString("courseName")
      val score = item.optString("score")
      if (courseName.isBlank() && score.isBlank()) continue
      result += buildScoreUpdateItem(item)
    }
    return result
  }

  private fun buildScoreUpdateItem(item: JSONObject): JSONObject {
    return JSONObject().apply {
      put("fingerprint", scoreFingerprint(item))
      put("semester", item.optString("semester"))
      put("courseCode", item.optString("courseCode"))
      put("courseName", item.optString("courseName"))
      put("score", item.optString("score"))
      put("scoreIdentifier", item.optString("scoreIdentifier"))
      put("courseAttribute", item.optString("courseAttribute"))
      put("courseNature", item.optString("courseNature"))
    }
  }

  private fun buildSampleScoreArray(): JSONArray {
    return JSONArray().apply {
      put(JSONObject().apply {
        put("index", 1)
        put("semester", "2025-2026-2")
        put("courseCode", "TEST1001")
        put("courseName", "测试高等数学")
        put("score", "91")
        put("scoreIdentifier", "")
        put("credits", "4")
        put("totalHours", "64")
        put("assessmentMethod", "考试")
        put("courseAttribute", "必修")
        put("courseNature", "必修")
        put("isHighlighted", false)
        put("rawText", "测试高等数学 91")
      })
      put(JSONObject().apply {
        put("index", 2)
        put("semester", "2025-2026-2")
        put("courseCode", "TEST1002")
        put("courseName", "测试大学物理")
        put("score", "86")
        put("scoreIdentifier", "")
        put("credits", "3.5")
        put("totalHours", "56")
        put("assessmentMethod", "考试")
        put("courseAttribute", "必修")
        put("courseNature", "必修")
        put("isHighlighted", false)
        put("rawText", "测试大学物理 86")
      })
      put(JSONObject().apply {
        put("index", 3)
        put("semester", "2024-2025-2")
        put("courseCode", "TEST2001")
        put("courseName", "测试程序设计")
        put("score", "优")
        put("scoreIdentifier", "")
        put("credits", "2")
        put("totalHours", "32")
        put("assessmentMethod", "考查")
        put("courseAttribute", "任选")
        put("courseNature", "选修")
        put("isHighlighted", false)
        put("rawText", "测试程序设计 优")
      })
    }
  }

  private fun showScoreUpdateNotification(updatedItems: List<JSONObject>) {
    if (!hasPostNotificationPermission()) {
      appendDebugLog("SCORE_UPDATE", "WARN", "系统未授予通知权限，跳过成绩更新通知")
      return
    }
    ensureScoreUpdateNotificationChannel()
    val launchIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val title = if (updatedItems.size == 1) {
      "成绩有更新"
    } else {
      "成绩有 ${updatedItems.size} 项更新"
    }
    val preview = updatedItems.take(3).joinToString("；") { item ->
      "${item.optString("courseName").ifBlank { "未命名课程" }} ${item.optString("score").ifBlank { "--" }}"
    }
    val bigText = buildString {
      append("检测到新的成绩变动。")
      if (preview.isNotBlank()) {
        append("\n").append(preview)
      }
      append("\n点开成绩查询后会高亮本次更新项。")
    }
    getSystemService(NotificationManager::class.java).notify(
      SCORE_UPDATE_NOTIFICATION_ID,
      NotificationCompat.Builder(this, SCORE_UPDATE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(preview.ifBlank { "点击查看详情" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    )
  }

  private fun ensureScoreUpdateNotificationChannel() {
    val channel = NotificationChannel(
      SCORE_UPDATE_CHANNEL_ID,
      "成绩更新",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "用于提示成绩查询中出现新的成绩变动"
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      setShowBadge(true)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun cancelScoreUpdateNotification() {
    getSystemService(NotificationManager::class.java).cancel(SCORE_UPDATE_NOTIFICATION_ID)
  }

  private fun hasPostNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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

  private fun throwableSummary(error: Throwable): String {
    val frames = error.stackTrace
      .take(6)
      .joinToString(" | ") { frame ->
        "${frame.className}.${frame.methodName}:${frame.lineNumber}"
      }
    return buildString {
      append(error::class.java.name)
      error.message?.takeIf { it.isNotBlank() }?.let {
        append(": ")
        append(it)
      }
      if (frames.isNotBlank()) {
        append(" @ ")
        append(frames)
      }
      error.cause?.let { cause ->
        append(" | cause=")
        append(cause::class.java.name)
        cause.message?.takeIf { it.isNotBlank() }?.let { causeMessage ->
          append(": ")
          append(causeMessage)
        }
      }
    }
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
    persistCredentials(username, password)
  }

  private fun persistCredentials(username: String, password: String) {
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

  private fun migrateTimetableCacheParserIfNeeded() {
    val storedVersion = prefs.getInt(PREF_TIMETABLE_CACHE_PARSER_VERSION, 0)
    if (storedVersion >= CURRENT_TIMETABLE_CACHE_PARSER_VERSION) {
      return
    }

    val rawFile = File(filesDir, CACHE_RAW_HTML_FILE)
    if (!rawFile.exists() || rawFile.length() <= 0L) {
      return
    }

    runCatching {
      val html = rawFile.readText(Charsets.UTF_8)
      TimetableSemesterStore.updateFromTimetableHtml(this, html)
      val courses = TimetableParser.parse(html)
      if (courses.isEmpty()) {
        throw IllegalStateException("原始课表 HTML 重解析结果为空")
      }

      File(filesDir, CACHE_JSON_FILE).writeText(TimetableRenderer.toJson(courses), Charsets.UTF_8)
      File(filesDir, GENERATED_HOME_HTML_FILE).writeText(
        TimetableRenderer.toHomeHtml(this, courses),
        Charsets.UTF_8
      )
      File(filesDir, GENERATED_CACHE_HTML_FILE).writeText(
        TimetableRenderer.toHtml(this, courses),
        Charsets.UTF_8
      )
      prefs.edit()
        .putInt(PREF_TIMETABLE_CACHE_PARSER_VERSION, CURRENT_TIMETABLE_CACHE_PARSER_VERSION)
        .apply()
      appendDebugLog("TIMETABLE_CACHE", "SUCCESS", "已使用新版解析器重建本地课表缓存，courses=${courses.size}")
    }.onFailure { error ->
      appendDebugLog("TIMETABLE_CACHE", "WARN", "新版解析器重建本地课表缓存失败：${error.message ?: "unknown"}")
    }
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
