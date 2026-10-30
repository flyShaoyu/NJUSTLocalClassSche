package com.classsche.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivityCampusToolsBinding
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors

class CampusToolsActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_PAGE = "campus_page"
    const val EXTRA_EDIT_ID = "edit_schedule_id"
    const val EXTRA_OPEN_TIMETABLE = "show_timetable"
    const val EXTRA_OPEN_LOGIN = "show_login"
    const val REQUEST_CODE = 720
    private const val LOCAL = "https://classsche.local/campus-tools/"
  }
  private lateinit var binding: ActivityCampusToolsBinding
  private val worker = Executors.newSingleThreadExecutor()
  private val inFlight = mutableSetOf<String>()
  private var localDocumentReady = false
  private var openingTool = false
  private val evaluation by lazy { TeachingEvaluationClient.create(this) }
  @Volatile private var evaluationPlan: TeachingEvaluationEngine.Plan? = null
  private val page by lazy { intent.getStringExtra(EXTRA_PAGE).orEmpty() }
  private val client by lazy { CampusDataClient(logger = { scope, state, message -> AppDebugLog.append(this, scope, state, message) }) }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityCampusToolsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    val title = when (page) {
      "schedule" -> "添加日程"
      "rooms" -> "空闲教室"
      "sites" -> "常用网站"
      "library" -> "图书搜索"
      "courses" -> "课程搜索"
      "evaluation" -> "课程评教"
      "toolbox" -> "小工具"
      CourseEnrollmentFeature.PAGE -> CourseEnrollmentFeature.TITLE
      "teacher" -> intent.getStringExtra("teacher_name").orEmpty() + " · 教师课表"
      else -> "校园工具"
    }
    binding.toolbar.title = title
    if (page == "teacher") binding.toolbar.subtitle = intent.getStringExtra("semester")
    SecondaryPageToolbar.bind(binding.root, binding.toolbar)
    binding.toolbar.setNavigationOnClickListener { goBack() }
    with(binding.toolsWebView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      builtInZoomControls = false
      displayZoomControls = false
    }
    binding.toolsWebView.setBackgroundColor(Color.parseColor("#F5F7FB"))
    binding.toolsWebView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { localDocumentReady = false }
      override fun onPageFinished(view: WebView, url: String) {
        if (page != "teacher" && url == LOCAL && !isFinishing && !isDestroyed) {
          localDocumentReady = true
          view.evaluateJavascript("window.CampusUI && window.CampusUI.start();", null)
        }
      }
    }
    binding.toolsWebView.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
          val detail = "页面脚本执行错误（第 ${message.lineNumber()} 行），请返回后重新打开；若持续出现，请重新安装完整安装包"
          binding.browserError.text = detail
          binding.browserError.visibility = View.VISIBLE
          AppDebugLog.append(this@CampusToolsActivity, "CAMPUS_TOOLS", "FAIL", detail)
        }
        return true
      }
      override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        AlertDialog.Builder(this@CampusToolsActivity).setMessage(message)
          .setNegativeButton("取消") { _, _ -> result.cancel() }
          .setPositiveButton("确定") { _, _ -> result.confirm() }
          .setOnCancelListener { result.cancel() }.show()
        return true
      }
    }
    if (page == "teacher") loadTeacher() else {
      binding.toolsWebView.addJavascriptInterface(Bridge(), "CampusBridge")
      val updated = ResourceUpdateStore.readText(this, "campus-tools.html")
      val html = updated?.takeIf { it.contains("campus-typography-v8") && it.contains("native-start-v2") && (page!="evaluation" || it.contains("evaluation-submit-v1")) && (page !in listOf("toolbox",CourseEnrollmentFeature.PAGE) || it.contains("enrollment-catalog-v1")) }
        ?: runCatching { assets.open("campus-tools.html").bufferedReader().use { it.readText() } }.getOrNull()
      if (html.isNullOrBlank()) message("页面资源缺失，请重新安装完整安装包") else {
        val pageHtml=if(page==CourseEnrollmentFeature.PAGE)GeneralEducationCatalogStore.inject(this,html) else html
        binding.toolsWebView.loadDataWithBaseURL(LOCAL,pageHtml,"text/html","utf-8",LOCAL)
      }
    }
  }

  private fun localPage(): Boolean = !isFinishing && !isDestroyed && page != "teacher" && localDocumentReady && binding.toolsWebView.url == LOCAL
  private fun quote(value: String) = JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

  private fun reply(id: String, packet: JSONObject) {
    if (isFinishing || isDestroyed || !localPage()) return
    binding.toolsWebView.evaluateJavascript("window.CampusCallbacks && window.CampusCallbacks.receive(${quote(id)},${quote(packet.toString())});", null)
  }

  private fun evaluationProgress(id: String, value: TeachingEvaluationEngine.Progress) {
    val json=TeachingEvaluationJson.progress(value).toString()
    runOnUiThread {
      if(localPage())binding.toolsWebView.evaluateJavascript("window.CampusCallbacks && window.CampusCallbacks.progress && window.CampusCallbacks.progress(${quote(id)},${quote(json)});",null)
    }
  }

  private fun bootstrap(): JSONObject {
    val semester = LocalScheduleStore.semester(this)
    val calendar = TimetableSemesterStore.resolveCalendar(semester)
    val today = LocalDate.now()
    val semesters = (listOf(semester) + TimetableSemesterStore.readCatalog(this).availableSemesters).distinct()
    return JSONObject().put("page", page).put("semester", semester).put("semesters", JSONArray(semesters))
      .put("enrollment",CourseEnrollmentFeature.status())
      .put("today", today.toString()).put("weekday", today.dayOfWeek.value)
      .put("week1Monday", calendar.week1Monday?.toString().orEmpty())
      .put("week", (TimetableSemesterStore.weekForDate(calendar, today) ?: 1).coerceIn(1, 30))
      .put("editId", intent.getStringExtra(EXTRA_EDIT_ID).orEmpty())
  }

  private fun favorites(): JSONArray {
    val raw = getSharedPreferences("library_favorites", MODE_PRIVATE).getString("books", "[]") ?: "[]"
    return runCatching { JSONArray(raw) }.getOrElse { throw IllegalStateException("收藏数据解析错误，原收藏已保留") }
  }

  private fun toggleFavorite(book: JSONObject): JSONObject {
    val id = book.optString("id")
    require(id.isNotBlank() && id.length <= 200 && book.optString("title").length in 1..300) { "图书信息不完整，不能收藏" }
    val list = favorites()
    val index = (0 until list.length()).firstOrNull { list.getJSONObject(it).optString("id") == id }
    if (index != null) list.remove(index) else {
      require(list.length() < 500) { "最多收藏 500 本图书" }
      val clean = JSONObject()
      for (key in listOf("id", "title", "author", "publisher", "year", "callNumber")) clean.put(key, book.optString(key).take(2000))
      clean.put("copies", book.optInt("copies")).put("available", book.optInt("available"))
      list.put(clean)
    }
    check(getSharedPreferences("library_favorites", MODE_PRIVATE).edit().putString("books", list.toString()).commit()) { "收藏保存失败，请重试" }
    return JSONObject().put("books", list)
  }

  private inner class Bridge {
    @JavascriptInterface fun setSection(section: String) = runOnUiThread {
      if (!localPage()) return@runOnUiThread
      val title = when (page to section) {
        "schedule" to "list" -> "添加日程"
        "schedule" to "new" -> "新建日程"
        "schedule" to "edit" -> "编辑日程"
        "library" to "search" -> "图书搜索"
        "library" to "favorites" -> "图书收藏"
        "library" to "account" -> "我的图书馆"
        "library" to "book" -> "馆藏详情"
        "courses" to "course" -> "课程搜索"
        "courses" to "teacher" -> "教师课表"
        else -> return@runOnUiThread
      }
      binding.toolbar.title = title
    }
    @JavascriptInterface fun request(id: String, action: String, payload: String) {
      runOnUiThread {
        if (!localPage() || id.length > 40 || payload.length > 20000) return@runOnUiThread
        if(action.startsWith("enrollment.")) {
          val result=if(action=="enrollment.status")JSONObject().put("ok",true).put("data",CourseEnrollmentFeature.status()) else CourseEnrollmentFeature.unavailable()
          reply(id,result); return@runOnUiThread
        }
        if(action.startsWith("evaluation.") && page!="evaluation") {
          reply(id,JSONObject().put("ok",false).put("error","请从课程评教页面发起操作")); return@runOnUiThread
        }
        if ((action.startsWith("evaluation.") && inFlight.any { it.startsWith("evaluation.") }) || !inFlight.add(action)) {
          reply(id, JSONObject().put("ok", false).put("error", "当前操作正在进行，请稍候"))
          return@runOnUiThread
        }
        val dispatch: () -> Unit = { worker.execute {
          val packet = try {
            val input = JSONObject(payload)
            val data = when (action) {
              "bootstrap" -> bootstrap()
              "schedules.list" -> JSONObject().put("items", LocalScheduleStore.read(this@CampusToolsActivity))
              "schedules.save" -> {
                val item = LocalScheduleStore.save(this@CampusToolsActivity, input)
                syncScheduleNotifications()
                JSONObject().put("item", item)
              }
              "schedules.delete" -> {
                LocalScheduleStore.delete(this@CampusToolsActivity, input.getString("id"))
                syncScheduleNotifications()
                JSONObject()
              }
              "rooms.filters" -> client.roomFilters(input.optString("campus", "01"))
              "rooms.query" -> client.rooms(input)
              "library.status" -> { require(page=="library") { "请从图书馆页面发起" };client.readerStatus() }
              "library.search" -> client.searchBooks(input.optString("keyword"), input.optInt("page", 1))
              "library.detail" -> client.bookDetail(input.optString("id"))
              "favorites.list" -> JSONObject().put("books", favorites())
              "favorites.toggle" -> toggleFavorite(input.getJSONObject("book"))
              "courses.search" -> client.searchCourses(input.optString("semester"), input.optString("keyword"), input.optString("teacher"))
              "teachers.search" -> client.teachers(input.optString("keyword"))
              "evaluation.scan" -> {
                evaluationPlan = null
                val plan=evaluation.scan(TeachingEvaluationJson.settings(input)) { evaluationProgress(id,it) }
                evaluationPlan=plan
                TeachingEvaluationJson.plan(plan)
              }
              "evaluation.submit" -> {
                val result=evaluation.submit(input.getString("id")) { evaluationProgress(id,it) }
                evaluationPlan=null
                AppDebugLog.append(this@CampusToolsActivity,"EVALUATION","INFO","评教操作结束，已确认 ${result.items.count { it.state=="success" }} 门，停止=${result.stopped}")
                TeachingEvaluationJson.outcome(result)
              }
              else -> error("未知操作")
            }
            JSONObject().put("ok", true).put("data", data)
          } catch (error: Exception) {
            val step = when (action.substringBefore('.')) {
              "schedules" -> "本地日程"
              "rooms" -> "空闲教室查询"
              "library" -> "图书馆查询"
              "favorites" -> "图书收藏"
              "courses" -> "课程搜索"
              "teachers" -> "教师搜索"
              "evaluation" -> "课程评教"
              else -> "加载页面"
            }
            val detail = "$step：${FailureDetails.describe(error)}"
            AppDebugLog.append(this@CampusToolsActivity, "CAMPUS_TOOLS", "FAIL", detail)
            JSONObject().put("ok", false).put("error", detail).put("code", if (error is CampusLoginRequired) "LOGIN_REQUIRED" else if(error is java.util.concurrent.CancellationException) "CANCELLED" else "QUERY_FAILED")
          }
          runOnUiThread { inFlight.remove(action); reply(id, packet) }
        } }
        if(action=="evaluation.submit") {
          val plan=evaluationPlan
          val requested=runCatching { JSONObject(payload).optString("id") }.getOrNull()
          if(plan==null || plan.id!=requested || plan.courses.isEmpty()) {
            inFlight.remove(action); reply(id,JSONObject().put("ok",false).put("error","没有有效预览，请重新读取待评课程"))
          } else {
            fun cancel() { inFlight.remove(action); reply(id,JSONObject().put("ok",false).put("error","已取消提交，尚未发送评价").put("code","CANCELLED")) }
            val names=plan.courses.take(20).joinToString("\n") { "• ${it.course.title}" }
            val message="将向学校提交 ${plan.courses.size} 门课程评价。\n普通题选择第 ${plan.settings.option+1} 项，最后一题选择第 ${plan.settings.lastOption+1} 项。\n完整逐题内容见页面预览。\n\n$names"+(if(plan.courses.size>20)"\n……共 ${plan.courses.size} 门" else "")
            AlertDialog.Builder(this@CampusToolsActivity).setTitle("确认一键评教")
              .setMessage(message).setNegativeButton("取消") { _,_->cancel() }
              .setPositiveButton("确认提交 ${plan.courses.size} 门") { _,_->dispatch() }.setOnCancelListener { cancel() }.show()
          }
        } else dispatch()
      }
    }

    @JavascriptInterface fun cancelEvaluation() = runOnUiThread {
      if(localPage() && page=="evaluation")evaluation.cancel()
    }

    @JavascriptInterface fun openTool(tool: String) = runOnUiThread {
      if(localPage() && page=="toolbox" && tool==CourseEnrollmentFeature.PAGE && !CourseEnrollmentFeature.browseEnabled) {
        android.widget.Toast.makeText(this@CampusToolsActivity,CourseEnrollmentFeature.UNAVAILABLE_MESSAGE,android.widget.Toast.LENGTH_SHORT).show()
        return@runOnUiThread
      }
      if(localPage() && page=="toolbox" && !openingTool && tool in listOf("courses","evaluation",CourseEnrollmentFeature.PAGE)) {
        openingTool = true
        startActivityForResult(Intent(this@CampusToolsActivity,CampusToolsActivity::class.java).putExtra(EXTRA_PAGE,tool),721)
      }
    }

    @JavascriptInterface fun showTimetable() = runOnUiThread {
      if (localPage()) { setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_TIMETABLE, true)); finish() }
    }
    @JavascriptInterface fun openLogin() = runOnUiThread {
      if (localPage()) {
        if (page == "library") startActivityForResult(Intent(this@CampusToolsActivity, LibraryLoginActivity::class.java),722)
        else { setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_LOGIN, true)); finish() }
      }
    }
    @JavascriptInterface fun openUrl(url: String) = runOnUiThread {
      if (localPage() && CampusBrowserActivity.allowedUrl(url)) startActivity(Intent(this@CampusToolsActivity, CampusBrowserActivity::class.java).putExtra("url", url).putExtra("private_library",url.startsWith(CampusDataClient.LIBRARY)))
    }
    @JavascriptInterface fun openTeacher(id: String, name: String, semester: String) = runOnUiThread {
      if (localPage() && id.length in 1..200 && name.length in 1..100 && semester.matches(Regex("\\d{4}-\\d{4}-[123]"))) {
        startActivityForResult(Intent(this@CampusToolsActivity, CampusToolsActivity::class.java).putExtra(EXTRA_PAGE, "teacher")
          .putExtra("teacher_id", id).putExtra("teacher_name", name).putExtra("semester", semester), 721)
      }
    }
  }

  private fun loadTeacher() {
    message("正在获取教师课表…")
    worker.execute {
      try {
        val semester = intent.getStringExtra("semester").orEmpty()
        val courses = client.teacherTimetable(semester, intent.getStringExtra("teacher_id").orEmpty())
        val html = TimetableRenderer.toHtml(this, courses, semester)
        runOnUiThread { if (!isFinishing && !isDestroyed) binding.toolsWebView.loadDataWithBaseURL(LOCAL + "teacher-timetable/", html, "text/html", "utf-8", null) }
      } catch (error: Exception) {
        val detail = "获取教师课表：${FailureDetails.describe(error)}"
        AppDebugLog.append(this, "TEACHER_TIMETABLE", "FAIL", detail)
        runOnUiThread {
          if (!isFinishing && !isDestroyed) {
            message(detail)
            AlertDialog.Builder(this).setMessage(detail).setNegativeButton("返回") { _, _ -> finish() }
              .setPositiveButton(if (error is CampusLoginRequired) "前往登录" else "重试") { _, _ ->
                if (error is CampusLoginRequired) { setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_LOGIN, true)); finish() }
                else loadTeacher()
              }.show()
          }
        }
      }
    }
  }

  private fun syncScheduleNotifications() {
    runCatching { CourseNotificationScheduler.sync(this) }.onFailure {
      AppDebugLog.append(this, "LOCAL_SCHEDULE", "FAIL", "日程已保存，提醒计划更新失败：${FailureDetails.describe(it)}")
    }
  }

  private fun message(text: String) {
    val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    binding.toolsWebView.loadDataWithBaseURL(LOCAL, "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='background:#f5f7fb;color:#71869c;font-family:serif;text-align:center;padding:70px 24px;line-height:1.8'>$safe</body></html>", "text/html", "utf-8", null)
  }

  private fun goBack() {
    if(inFlight.any { it.startsWith("evaluation.") }) {
      AlertDialog.Builder(this).setMessage("评教操作尚未结束，可以停止尚未开始的课程，等待当前课程返回结果。")
        .setNegativeButton("继续等待",null).setPositiveButton("停止后续") { _,_->evaluation.cancel() }.show()
      return
    }
    if (page == "teacher") { finish(); return }
    binding.toolsWebView.evaluateJavascript("window.CampusUI ? window.CampusUI.back() : false") { handled -> if (handled != "true") finish() }
  }
  @Deprecated("Deprecated in Java") override fun onBackPressed() = goBack()
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if(requestCode==722 && page=="library" && localPage()) binding.toolsWebView.evaluateJavascript("window.CampusUI && window.CampusUI.refreshLibraryStatus && window.CampusUI.refreshLibraryStatus()",null)
    if(requestCode==721)openingTool=false
    if (requestCode == 721 && resultCode == RESULT_OK && data?.getBooleanExtra(EXTRA_OPEN_LOGIN, false) == true) {
      setResult(RESULT_OK, data); finish()
    }
  }
  override fun onDestroy() {
    if(page=="evaluation")evaluation.cancel()
    worker.shutdownNow()
    binding.toolsWebView.removeJavascriptInterface("CampusBridge")
    binding.toolsWebView.stopLoading()
    binding.toolsWebView.destroy()
    super.onDestroy()
  }
}
