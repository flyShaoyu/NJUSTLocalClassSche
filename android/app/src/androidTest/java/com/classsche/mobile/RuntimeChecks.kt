package com.classsche.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device-only checks: use the platform ICU and actual WebView/JS bridge, without external test libraries. */
class RuntimeChecks : Instrumentation() {
  private var phase = "all"
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase = arguments?.getString("phase") ?: "all"; start() }
  override fun onStart() {
    try {
      when (phase) { "uiStyle" -> uiStyle(); "mailNotifications" -> mailNotifications(); "schoolMailSendTest" -> schoolMailSendTest(); "schoolMailReal" -> schoolMailReal(); "schoolMailTls" -> schoolMailTls(); "schoolMail" -> {SchoolMailChecks.run(targetContext,::report);schoolMailUi()}; "libraryCredentials" -> libraryCredentials(); "examExpansion" -> examExpansion(); "homeToolbar" -> homeToolbar(); "refinement" -> refinement(); "roomsMulti" -> roomsMulti(); "libraryNativeForm" -> libraryNativeForm(); "libraryReal" -> libraryReal(); "libraryAccount" -> libraryAccount(); "libraryNew" -> libraryNew(); "creditDrag" -> creditDrag(); "presentation" -> presentation(); "enrollment" -> enrollmentCatalogue(); "evaluation" -> evaluationOffline(); "cookies" -> cookieDiagnostics(); "loanCookie" -> loanCookie(); "queries" -> queries(); "catalog" -> catalog(); "interactions" -> interactions(); else -> localPages() }
      finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Runtime checks passed: $phase\n") })
    } catch (error: Throwable) {
      finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
    }
  }
  private fun snapshot(name: String) {
    waitForIdleSync()
    SystemClock.sleep(650) // Wait for WebView compositor and sheet transitions before capture.
    uiAutomation.takeScreenshot()?.let { bitmap ->
      java.io.File(targetContext.getExternalFilesDir(null), "refinement-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
      bitmap.recycle()
    }
  }
  private fun report(text: String) = sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
  private fun <T> ui(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    runOnMainSync { result.set(runCatching(block)) }
    return result.get().getOrThrow()
  }
  private fun js(view: WebView, expression: String, timeoutSeconds: Long = 8): String {
    val latch = CountDownLatch(1); val result = AtomicReference<String>()
    ui { view.evaluateJavascript(expression) { result.set(it); latch.countDown() } }
    check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) { "WebView callback timeout" }
    return result.get()
  }
  private fun waitJs(view: WebView, expression: String, timeoutMs: Long = 20000): String {
    val deadline=SystemClock.uptimeMillis()+timeoutMs
    while(SystemClock.uptimeMillis()<deadline) {
      try { val value=js(view,expression,2); if(value=="true") return value }
      catch(error: IllegalStateException) { if(error.message!="WebView callback timeout") throw error }
      SystemClock.sleep(100)
    }
    error("WebView condition failed: $expression; ${js(view, "JSON.stringify({title:document.title,path:location.pathname})")}")
  }
  private fun launch(type: Class<out Activity>, extras: Intent.() -> Unit = {}): Activity =
    startActivitySync(Intent(targetContext, type).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); extras() })

  /** Test APK only: caller supplies one explicitly authorized recipient over a local RAM channel. */
  private fun schoolMailSendTest(){
    val account=SchoolMailAccountStore(targetContext).load()?:error("Real account must authenticate before send test")
    val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));server.soTimeout=120000
    report("MAIL_SEND_TEST_PORT="+server.localPort)
    val recipient=server.use{listener->listener.accept().use{socket->
      socket.soTimeout=15000;val text=socket.getInputStream().bufferedReader().readLine().orEmpty();check(text.length<=1000)
      val value=JSONObject(text).getString("recipient");check(SchoolMailPolicy.addresses(value).size==1)
      socket.getOutputStream().write("OK\n".toByteArray());socket.getOutputStream().flush();value
    }}
    val id=java.util.UUID.nameUUIDFromBytes(("classsche-school-mail-test-20261030:"+account.address+":"+recipient).toByteArray()).toString()
    SchoolMailOutbox(targetContext).use{outbox->
      val prior=outbox.find(id)
      if(prior!=null){report("Authorized test already has journal state="+prior.state+"; no duplicate send attempted; id="+id);check(prior.state=="accepted"){"Prior send not confirmed; refused resend"};return}
      val draft=SchoolMailDraft(recipient,"课表App邮箱功能验证 [20261030]","这是一封由课表App发出的学校邮箱收发功能测试邮件。\n仅用于验证SMTP发送，测试结束，无需回复。")
      val result=SchoolMailClient(account).send(id,draft,outbox)
      report("Authorized one-time test: state="+result.state+"; id="+id+"; "+result.detail)
      check(result.state=="accepted"){"Test was not confirmed accepted; journal retained; no automatic retry"}
      check(outbox.find(id)?.state=="accepted")
    }
  }

  /** Authorized .env EMAIL fields enter RAM through test APK only. No external mail is sent. */
  private fun schoolMailReal(){
    val activity=launch(SchoolMailActivity::class.java)
    try{
      val busy=SchoolMailActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
      val readyDeadline=SystemClock.uptimeMillis()+90000
      while(SystemClock.uptimeMillis()<readyDeadline&&ui{busy.getBoolean(activity)})SystemClock.sleep(100)
      check(!ui{busy.getBoolean(activity)}){"Previous mailbox read still in progress"}
      ui{SchoolMailActivity::class.java.getDeclaredMethod("settings").apply{isAccessible=true}.invoke(activity)}
      val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));server.soTimeout=120000
      var expectedAccount:SchoolMailAccount?=null
      report("MAIL_CREDENTIAL_PORT="+server.localPort)
      server.use{listener->listener.accept().use{socket->
        socket.soTimeout=15000;val input=socket.getInputStream().bufferedReader(Charsets.UTF_8);val line=StringBuilder()
        while(line.length<8192){val ch=input.read();if(ch<0||ch==10)break;line.append(ch.toChar())}
        val fields=JSONObject(line.toString());expectedAccount=SchoolMailAccount(fields.getString("email"),fields.getString("password"));val content=ui{activity.findViewById<android.widget.LinearLayout>(R.id.mailContent)}
        ui{
          content.findViewWithTag<android.widget.EditText>("mailAccount").setText(fields.getString("email"))
          content.findViewWithTag<android.widget.EditText>("mailSecret").setText(fields.getString("password"))
          content.findViewWithTag<android.widget.Button>("mailVerify").performClick()
        }
        socket.getOutputStream().write("OK\n".toByteArray());socket.getOutputStream().flush()
      }}
      val deadline=SystemClock.uptimeMillis()+120000
      while(SystemClock.uptimeMillis()<deadline&&ui{busy.getBoolean(activity)})SystemClock.sleep(100)
      check(!ui{busy.getBoolean(activity)}){"Real mail validation timed out"}
      val stored=SchoolMailAccountStore(targetContext).load()
      if(stored==null||stored!=expectedAccount){
        val detail=ui{activity.findViewById<android.widget.TextView>(R.id.mailStatus).text.toString()}
        val expected=expectedAccount?:error("Missing local test account")
        for((stage,operation) in listOf<Pair<String,()->Unit>>("IMAP" to {SchoolMailClient(expected).verifyReceiving()},"SMTP" to {SchoolMailClient(expected).verifySending()})){
          try{operation();report("Mail diagnostic $stage authenticated; no mail sent")}
          catch(e:Exception){
            val messages=generateSequence(e as Throwable?){it.cause}.take(6).joinToString(" | "){it.message.orEmpty()}
            val secrets=listOf(expected.address,expected.secret,android.util.Base64.encodeToString(expected.address.toByteArray(),android.util.Base64.NO_WRAP),android.util.Base64.encodeToString(expected.secret.toByteArray(),android.util.Base64.NO_WRAP))
            var safe=messages;for(value in secrets.filter{it.isNotBlank()})safe=safe.replace(value,"[redacted]",true)
            safe=safe.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+"),"[address]").replace(Regex("[A-Za-z0-9+/=_-]{24,}"),"[identifier]").take(500)
            report("Mail diagnostic $stage failure (redacted server response): "+safe)
          }
        }
        error("Real mail connection failed: $detail")
      }
      val client=SchoolMailClient(stored)
      val page=client.list();check(page.items.size<=30)
      if(page.items.isNotEmpty()){
        val body=client.read("INBOX",page.items.first().uid,page.validity)
        check(body.uid==page.items.first().uid)
      }
      SchoolMailPolling.observe(targetContext,stored,page,false)
      check(SchoolMailPolling.interval(targetContext)==0){"Real validation must not silently enable polling"}
      report("Real mail validation passed: IMAP and SMTP authenticated without sending mail; account saved encrypted; inbox headers and newest text read through native client. No addresses, subjects, bodies or app code logged; polling remains off")
    }finally{ui{activity.finish()}}
  }

  private fun schoolMailTls(){
    for((host,port) in listOf(SchoolMailPolicy.IMAP_HOST to 993,SchoolMailPolicy.SMTP_HOST to 465)){
      val socket=javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket
      socket.use{
        it.connect(java.net.InetSocketAddress(host,port),12000);it.soTimeout=12000
        it.sslParameters=it.sslParameters.apply{endpointIdentificationAlgorithm="HTTPS"};it.startHandshake()
        val line=it.inputStream.bufferedReader().readLine().orEmpty();check(if(port==993)line.startsWith("* OK",true)else line.startsWith("220")){"Mail server greeting not received"}
        report("Android verified TLS and public greeting: $host:$port; no AUTH, LOGIN, mailbox access or mail sent")
      }
    }
  }

  private fun uiStyle(){
    fun views(root:android.view.View):List<android.view.View> = listOf(root)+if(root is android.view.ViewGroup)(0 until root.childCount).flatMap{views(root.getChildAt(it))}else emptyList()
    val site=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"sites")};waitForIdleSync();SystemClock.sleep(150)
    val reference=ui{site.findViewById<android.view.View>(R.id.toolbar).height};ui{site.finish()}
    val pages=listOf(NotificationSettingsActivity::class.java,ScoreSyncSettingsActivity::class.java,TimetableSemesterSettingsActivity::class.java,LogViewerActivity::class.java,SchoolMailSettingsActivity::class.java,SchoolMailGuideActivity::class.java)
    for(type in pages){
      val activity=launch(type);waitForIdleSync();SystemClock.sleep(100)
      val toolbar=ui{activity.findViewById<android.view.View>(R.id.toolbar)?:activity.findViewById(R.id.mailToolbar)}
      check(ui{toolbar.height}==reference){"${type.simpleName} header differs from common-sites header"}
      ui{activity.finish()}
    }
    report("Uniform headers: common-sites, notification/score/semester/log settings, mail settings and guide use identical height")
    val factory=SchoolMailRuntime.clientFactory
    val pollPrefs=targetContext.getSharedPreferences("school_mail_poll",0);val pollSnapshot=pollPrefs.all.toMap()
    targetContext.getSystemService(android.app.job.JobScheduler::class.java).cancel(74120)
    MailFixture.connectFails=false;MailFixture.inbox.clear();MailFixture.sent.clear()
    SchoolMailRuntime.clientFactory={SchoolMailClient(it,MailFixture.session())}
    var mail:Activity?=null
    try{
      if(SchoolMailAccountStore(targetContext).load()!=null){
        val activity=launch(SchoolMailActivity::class.java);mail=activity
        val busy=SchoolMailActivity::class.java.getDeclaredField("busy").apply{isAccessible=true}
        val deadline=SystemClock.uptimeMillis()+8000
        while(ui{busy.getBoolean(activity)}&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        check(!ui{busy.getBoolean(activity)})
        val toolbar=ui{activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.mailToolbar)}
        waitForIdleSync()
        val layoutDeadline=SystemClock.uptimeMillis()+4000
        while(ui{toolbar.height}!=reference&&SystemClock.uptimeMillis()<layoutDeadline)SystemClock.sleep(50)
        check(ui{toolbar.height}==reference){"Mail header=${ui{toolbar.height}}, expected=$reference, layout=${ui{toolbar.layoutParams.height}}"}
        check(ui{(0 until toolbar.menu.size()).none{toolbar.menu.getItem(it).title.toString()=="写信"}})
        val popup=androidx.appcompat.view.ContextThemeWrapper(activity,ui{toolbar.popupTheme})
        val color=popup.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).let{val value=it.getColor(0,0);it.recycle();value}
        check(color==android.graphics.Color.WHITE)
        val content=ui{activity.findViewById<android.widget.LinearLayout>(R.id.mailContent)}
        check(ui{content.findViewWithTag<android.view.View>("mailFolderSelector")!=null})
        check(ui{views(content).filterIsInstance<android.widget.TextView>().none{it.text.toString().contains("不会自动执行邮件")}})
        val rows=listOf(SchoolMailOutbox.Entry("synthetic","synthetic","<synthetic@example.invalid>","accepted",System.currentTimeMillis(),"模拟发送结果"))
        ui{
          SchoolMailActivity::class.java.getDeclaredMethod("clear").apply{isAccessible=true}.invoke(activity)
          SchoolMailActivity::class.java.getDeclaredMethod("renderOutbox",List::class.java).apply{isAccessible=true}.invoke(activity,rows)
        }
        val identifier=ui{views(content).filterIsInstance<android.widget.TextView>().first{it.text.toString().startsWith("编号 ")}}
        check(!ui{identifier.isShown})
        ui{views(content).filterIsInstance<android.widget.Button>().first{it.text.toString()=="查看发送标识"}.performClick()}
        check(ui{identifier.isShown})
        report("Mail: no duplicate compose action or bottom notice, white overflow menu, clean folder selector and expandable outbox identifiers; fake receiving only")
      }
    }finally{
      ui{mail?.finish()};SchoolMailRuntime.clientFactory=factory
      val restore=pollPrefs.edit().clear()
      pollSnapshot.forEach{(key,value)->when(value){is String->restore.putString(key,value);is Boolean->restore.putBoolean(key,value);is Long->restore.putLong(key,value);is Int->restore.putInt(key,value);is Float->restore.putFloat(key,value)}}
      restore.commit();SchoolMailPolling.schedule(targetContext)
    }
    val settings=launch(SchoolMailSettingsActivity::class.java);waitForIdleSync()
    val selector=ui{settings.findViewById<android.widget.LinearLayout>(R.id.mailContent).findViewWithTag<android.view.ViewGroup>("mailUpdateInterval")}
    val row=ui{selector.getChildAt(0) as android.view.ViewGroup}
    val centers=ui{(0 until row.childCount).map{val v=row.getChildAt(it);(v.top+v.bottom)/2}}
    check(centers.maxOrNull()!!-centers.minOrNull()!!<=2)
    ui{settings.finish()}
    report("Update interval arrow and text are vertically aligned; all long intervals verified by mailNotifications phase")
  }

  private fun mailNotifications(){
    SchoolMailChecks.reuse(::report)
    MailNotificationChecks.run(targetContext,::report)
    report("Opening native mail settings")
    val settings=launch(SchoolMailSettingsActivity::class.java)
    check(ui{settings.findViewById<android.widget.LinearLayout>(R.id.mailContent).findViewWithTag<android.view.View>("mailUpdateInterval")!=null})
    check(ui{settings.findViewById<android.widget.LinearLayout>(R.id.mailContent).findViewWithTag<android.view.View>("mailNotificationSwitch")!=null})
    ui{settings.finish()}
    waitForIdleSync()
    report("Opening native mail guide")
    val guide=launch(SchoolMailGuideActivity::class.java)
    val content=ui{guide.findViewById<android.widget.LinearLayout>(R.id.mailContent)}
    check(ui{(0 until content.childCount).count{content.getChildAt(it) is android.widget.ImageView}}==6)
    ui{guide.finish()}
    report("Native mail settings and guide: update interval and notification switch share one page; six bundled guide images loaded")
  }

  private fun schoolMailUi(){
    if(SchoolMailAccountStore(targetContext).load()!=null){report("Native mailbox UI test skipped: an existing real account is configured; no mailbox opened by simulated test");return}
    val activity=launch(SchoolMailActivity::class.java)
    try{
      val mail=activity
      check((ui{mail.window.attributes.flags} and android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)
      check(SchoolMailPolling.interval(targetContext) in SchoolMailPolling.intervals)
      val fake=SchoolMailContent("INBOX",42,1,"模拟邮件","sender@example.invalid","reader@example.invalid","sender@example.invalid",0,"<script>原文作为文字显示</script>",listOf("模拟附件.pdf"),false,"<synthetic@example.invalid>","")
      ui{SchoolMailActivity::class.java.getDeclaredMethod("showMessage",SchoolMailContent::class.java).apply{isAccessible=true}.invoke(mail,fake)}
      check(ui{mail.findViewById<android.widget.LinearLayout>(R.id.mailContent).childCount}>3)
      ui{SchoolMailActivity::class.java.getDeclaredMethod("compose",SchoolMailContent::class.java).apply{isAccessible=true}.invoke(mail,fake)}
      val content=ui{mail.findViewById<android.widget.LinearLayout>(R.id.mailContent)}
      check(ui{content.findViewWithTag<android.widget.EditText>("mailTo").text.toString()}==fake.replyTo)
      check(ui{content.findViewWithTag<android.widget.EditText>("mailSubject").text.toString()}=="Re: 模拟邮件")
      check(ui{content.findViewWithTag<android.widget.Button>("mailSend").text.toString()}=="发送邮件")
      ui{mail.finish()}
      report("Native mailbox opens directly; secure window, update/notification settings separated; no real account or email used")
    }finally{ui{activity.finish()}}
  }

  private fun cookieDiagnostics() {
    ui { CookieManager.getInstance().setAcceptCookie(true) }
    val connection = URL(UniversityEndpoints.PORTAL_LOGIN).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false; connection.connectTimeout = 10000; connection.readTimeout = 10000
    val code = connection.responseCode
    val headers = connection.headerFields.filterKeys { it != null }
    connection.inputStream.close(); connection.disconnect()
    report("Public CAS GET HTTP $code; no credentials submitted")
    for (cookie in headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()) {
      val name = cookie.substringBefore('=').trim()
      val attrs = cookie.split(';').drop(1).map { it.substringBefore('=').trim() }.joinToString(",")
      try {
        WebViewLoginCookieStore.receive(UniversityEndpoints.PORTAL_LOGIN, mapOf("Set-Cookie" to listOf(cookie)))
        report("Cookie $name accepted; attributes=$attrs")
      } catch (error: Exception) { report("Cookie $name rejected; attributes=$attrs; ${error.message}") }
    }
    for (cookie in listOf("probe=1; Path=/; Secure; HttpOnly", "missing=; Path=/; Max-Age=0", "probe=; Path=/; Max-Age=0")) {
      WebViewLoginCookieStore.receive("https://classsche.local/", mapOf("Set-Cookie" to listOf(cookie)))
      report("Synthetic cookie ${cookie.substringBefore('=')} accepted")
    }
    WebViewLoginCookieStore.receive("https://ids.njust.edu.cn/authserver/login", mapOf("Set-Cookie" to listOf("platformMultilingual=zh_CN; Domain=edu.cn; Path=/; HttpOnly")))
    check(!LoginCookiePolicy.acceptsScope("https://ids.njust.edu.cn/", "bad=x; Domain=edu.cn"))
    check(LoginCookiePolicy.acceptsScope("https://ids.njust.edu.cn/", "parent=x; Domain=.njust.edu.cn"))
    report("Public-suffix cookie ignored; university parent domain allowed")
    val diagnosticStore = object : LoginCookieStore {
      override fun read(url: String) = ""
      override fun flush() = WebViewLoginCookieStore.flush()
      override fun receive(url: String, headers: Map<String, List<String>>) {
        for (cookie in headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()) {
          val name = cookie.substringBefore('=').trim()
          val attrs = cookie.split(';').drop(1).map { part ->
            val key = part.substringBefore('=').trim()
            if (key.lowercase() in listOf("domain", "path", "samesite", "secure", "httponly", "max-age", "expires")) part.trim() else key
          }.joinToString(";")
          try { WebViewLoginCookieStore.receive(url, mapOf("Set-Cookie" to listOf(cookie))); report("${URL(url).host} cookie $name accepted") }
          catch (error: Exception) { report("${URL(url).host} cookie $name rejected; $attrs"); throw error }
        }
      }
    }
    try {
      HeadlessLoginClient({ scope, state, message -> report("$scope $state $message") }, sharedCookies = diagnosticStore)
        .login(UniversityEndpoints.LOGIN, UniversityEndpoints.TIMETABLE, "", "")
      report("Cookie-only teaching login succeeded")
    } catch (error: Exception) { report("Cookie-only login result: ${error.message}") }
  }

  private fun localPages() {
    // This calls the actual store initializer on Android ICU, not the desktop JVM.
    val template = targetContext.assets.open("score-view.html").bufferedReader().use { it.readText() }
    val html = GeneralEducationCatalogStore.inject(targetContext, template)
    check(html.contains("mobileUpdateAvailable"))
    report("Android ICU/catalog injection passed")
    val reference = launch(NotificationSettingsActivity::class.java)
    SystemClock.sleep(300)
    val referenceHeight = ui { reference.findViewById<android.view.View>(R.id.toolbar).height }
    ui { reference.finish() }
    report("Existing settings toolbar height=$referenceHeight")
    for (page in listOf("schedule", "sites", "library", "courses", "rooms")) {
      val activity = launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE, page) }
      val view = ui { activity.findViewById<WebView>(R.id.toolsWebView) }
      waitJs(view, "typeof meta !== 'undefined' && meta.page === '$page'")
      check(ui { view.url } == "https://classsche.local/campus-tools/")
      val height = ui { activity.findViewById<android.view.View>(R.id.toolbar).height }
      check(height == referenceHeight) { "$page toolbar=$height; expected=$referenceHeight" }
      val font = js(view, "getComputedStyle(document.body).fontFamily")
      val background = js(view, "getComputedStyle(document.body).backgroundColor")
      report("$page bootstrap passed; toolbar=$height; font=$font; background=$background")
      if (page == "schedule") {
        waitJs(view, "pending.size === 0")
        check(js(view, "document.body.innerText.includes('日程')") == "true")
      }
      ui { activity.finish() }
    }
    val main = launch(MainActivity::class.java)
    ui { MainActivity::class.java.getDeclaredMethod("showCachedScorePage").apply { isAccessible = true }.invoke(main) }
    val score = ui { main.findViewById<WebView>(R.id.contentWebView) }
    waitJs(score, "!!window.GeneralEducationPage")
    check(ui { score.url } == "https://classsche.local/score-view/")
    js(score, "window.GeneralEducationPage.open()")
    js(score, "window.GeneralEducationPage.catalog()")
    val monitor = addMonitor(GeneralEducationUpdateActivity::class.java.name, null, false)
    ui { score.evaluateJavascript("window.GeneralEducationPage.toolbarAction()",null) }
    val updater = waitForMonitorWithTimeout(monitor,8000) ?: error("Catalogue update link did not reach native activity")
    removeMonitor(monitor)
    SystemClock.sleep(300)
    val updaterHeight=ui { updater.findViewById<android.view.View>(R.id.toolbar).height }
    check(updaterHeight == referenceHeight) { "Catalogue toolbar=$updaterHeight; expected=$referenceHeight" }
    ui { updater.finish() }
    report("Score page and general education statistics opened without crash")
    ui { main.finish() }
  }

  private fun queries() {
    val client = CampusDataClient(logger = { scope, state, message -> report("$scope $state $message") })
    val semester = LocalScheduleStore.semester(targetContext)
    val filters = client.roomFilters("01")
    check(filters.getJSONArray("buildings").length() > 0)
    report("Room filters loaded")
    val calendar = TimetableSemesterStore.resolveCalendar(semester)
    val today = java.time.LocalDate.now()
    val rooms = client.rooms(JSONObject().put("semester", semester).put("campus", "01").put("week", TimetableSemesterStore.weekForDate(calendar,today) ?: 1)
      .put("weekday",today.dayOfWeek.value).put("startPeriod",1).put("endPeriod",3).put("building",""))
    report("Rooms query completed: ${rooms.getJSONArray("rooms").length()} rooms")
    val courses = client.searchCourses(semester, "高等数学", "")
    report("Course search completed: ${courses.getInt("total")} results")
    val teachers = client.teachers("马立丰").getJSONArray("teachers")
    check(teachers.length()>0)
    val teacher = teachers.getJSONObject(0)
    val timetable = client.teacherTimetable(semester, teacher.getString("id"))
    report("Teacher search/timetable completed: ${timetable.size} courses")
    val books = client.searchBooks("活着",1).getJSONArray("books")
    check(books.length()>0)
    client.bookDetail(books.getJSONObject(0).getString("id"))
    report("Library search/detail completed: ${books.length()} books")
    try { report("Library loans completed: ${client.loans().getJSONArray("loans").length()}") }
    catch (error: CampusLoginRequired) { report("Library loans requires reader login, clear error returned") }
    val main = launch(MainActivity::class.java)
    ui { MainActivity::class.java.getDeclaredMethod("startSilentTimetableRefresh", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(main,true) }
    val busy = MainActivity::class.java.getDeclaredField("isAutoUpdating").apply { isAccessible = true }
    val deadline = SystemClock.uptimeMillis()+90000
    while (ui { busy.getBoolean(main) } && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(250)
    check(!ui { busy.getBoolean(main) }) { "Manual refresh did not complete" }
    check(AppDebugLog.read(targetContext).contains("课表缓存已更新")) { "Refresh failed: check app log" }
    var resultShown=false
    repeat(30) {
      val resultWindow=uiAutomation.rootInActiveWindow
      if(resultWindow != null && (resultWindow.findAccessibilityNodeInfosByText("更新完成").isNotEmpty() || resultWindow.findAccessibilityNodeInfosByText("部分数据更新失败").isNotEmpty())) resultShown=true
      if(!resultShown) SystemClock.sleep(100)
    }
    check(resultShown) { "Refresh result dialog missing" }
    report("Homepage refresh completed, busy state cleared, result dialog shown")
    ui { main.finish() }
  }

  private fun loanCookie() {
    val store = object : LoginCookieStore {
      override fun read(url: String) = WebViewLoginCookieStore.read(url)
      override fun flush() = WebViewLoginCookieStore.flush()
      override fun receive(url: String, headers: Map<String, List<String>>) {
        for (cookie in headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()) {
          val name = cookie.substringBefore('=').trim()
          val attributes = cookie.split(';').drop(1).joinToString(";")
          val value = cookie.substringBefore(';').substringAfter('=', "")
          report("Cookie metadata: ${URL(url).protocol}://${URL(url).host}${URL(url).path} $name valueLength=${value.length} $attributes")
        }
        WebViewLoginCookieStore.receive(url, headers)
      }
    }
    try { CampusDataClient(logger = { s,t,m -> report("$s $t $m") },cookies=store).loans(); report("Loans completed") }
    catch (error: Exception) { report("Loans result: ${error.message}") }
  }

  private fun catalog() {
    val activity = launch(GeneralEducationUpdateActivity::class.java)
    val active = GeneralEducationUpdateActivity::class.java.getDeclaredField("active").apply { isAccessible = true }
    val deadline = SystemClock.uptimeMillis()+55000
    var reported = false
    while (ui { active.getBoolean(activity) } && SystemClock.uptimeMillis()<deadline) {
      SystemClock.sleep(300)
      if (!reported && SystemClock.uptimeMillis()>deadline-50000) {
        reported = true
        val view = ui { activity.findViewById<WebView>(R.id.browser) }
        val flags = listOf("active", "foreground", "reading", "saving").joinToString { name ->
          "$name="+ui { GeneralEducationUpdateActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) }
        }
        report("Catalogue state: $flags; native URL=${ui { view.url }}")
        report("Catalogue DOM: "+js(view,"JSON.stringify({url:location.href,ready:document.readyState,rows:document.querySelectorAll('.wp_articlecontent table tr').length})"))
      }
    }
    val catalog = GeneralEducationCatalogStore.read(targetContext)
    if (ui { activity.isFinishing } && catalog != null) {
      check(catalog.getJSONArray("courses").length()==742)
      report("Mobile WebView fetched and saved 742 catalogue rows")
    } else {
      val status = ui { activity.findViewById<android.widget.TextView>(R.id.status).text.toString() }
      ui { activity.finish() }
      error("Mobile catalogue result: $status")
    }
  }

  private fun interactions() {
    val schedule = launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,"schedule") }
    val scheduleView = ui { schedule.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(scheduleView,"typeof meta !== 'undefined' && meta.page === 'schedule' && pending.size === 0")
    js(scheduleView,"document.querySelector('.fab').click()")
    waitJs(scheduleView,"!!document.getElementById('scheduleForm')")
    val marker = "runtime-check-"+System.nanoTime()
    var createdId = ""
    try {
      js(scheduleView,"var f=document.getElementById('scheduleForm'); f.elements.title.value='验证日程'; f.elements.weeksText.value='1-16'; f.elements.startTime.value='10:20'; f.elements.endTime.value='13:10'; f.elements.location.value=''; f.elements.notes.value='$marker'; f.requestSubmit();")
      repeat(50) {
        val items = LocalScheduleStore.read(targetContext)
        for(i in 0 until items.length()) { val item=items.getJSONObject(i); if(item.optString("notes")==marker) createdId=item.getString("id") }
        if(createdId.isNotEmpty()) return@repeat
        SystemClock.sleep(100)
      }
      check(createdId.isNotEmpty()) { "Native schedule save did not complete" }
      val course = LocalScheduleStore.courses(targetContext).first { it.courseCode=="local:$createdId" }
      check(course.startTime=="10:20" && course.endTime=="13:10" && course.classroom.isEmpty() && course.notes==marker)
      report("Native schedule save passed: custom time across breaks, optional location, notes; shown in timetable data")
    } finally { if(createdId.isNotEmpty()) LocalScheduleStore.delete(targetContext,createdId); ui { if(!schedule.isFinishing) schedule.finish() } }
    val rooms=launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,"rooms") }
    val roomView=ui { rooms.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(roomView,"!!document.querySelector('[data-building]')")
    check(js(roomView,"document.querySelector('.filter-deck').getBoundingClientRect().bottom <= innerHeight+1")=="true")
    js(roomView,"document.getElementById('queryRooms').click()")
    waitJs(roomView,"!!document.querySelector('.room-grid')")
    report("Room UI queried and rendered; bottom filters fit the viewport")
    ui { rooms.finish() }
    val library=launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,"library") }
    val libraryView=ui { library.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(libraryView,"!!document.getElementById('bookSearch')")
    js(libraryView,"document.getElementById('bookKeyword').value='活着'; document.getElementById('bookSearch').requestSubmit()")
    waitJs(libraryView,"!!document.querySelector('[data-book]')")
    js(libraryView,"document.querySelector('[data-book]').click()")
    waitJs(libraryView,"!!document.querySelector('.holding')")
    report("Library UI search and holdings modal passed")
    ui { library.finish() }
    val courses=launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,"courses") }
    val courseView=ui { courses.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(courseView,"!!document.getElementById('teacherMode')")
    js(courseView,"document.getElementById('teacherMode').click(); document.getElementById('courseKeyword').value='马立丰'; document.getElementById('courseSearch').requestSubmit()")
    waitJs(courseView,"!!document.querySelector('[data-teacher-id]')")
    val monitor=addMonitor(CampusToolsActivity::class.java.name,null,false)
    js(courseView,"document.querySelector('[data-teacher-id]').click()")
    val teacher=waitForMonitorWithTimeout(monitor,8000) ?: error("Teacher timetable activity did not open")
    removeMonitor(monitor)
    val teacherView=ui { teacher.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(teacherView,"typeof courses !== 'undefined' && courses.length > 0")
    check(js(teacherView,"!document.querySelector('#local-schedule-edit') || getComputedStyle(document.querySelector('#local-schedule-edit')).display === 'none'")=="true")
    report("Teacher search opens real timetable with the existing timetable UI")
    ui { teacher.finish(); courses.finish() }
  }

  /** Inject a transport before any evaluation action. The school's server is never contacted. */
  private fun evaluationOffline() {
    var posts=0;var completed=false
    val transport=object: EvaluationTransport {
      override fun get(url: String): org.jsoup.nodes.Document {
        val html=if(url==TeachingEvaluationParser.ENTRY) "<h1>学生评教</h1><table><tr><th>课程</th><th>状态</th><th>操作</th></tr><tr><td>模拟课程</td><td>${if(completed)"已完成" else "未完成"}</td><td><a href='xspj_edit.do?course=fixture'>${if(completed)"查看" else "评价"}</a></td></tr></table>"
        else "<form id=Form1 method=post action='xspj_save.do?course=fixture'><input type=hidden name=csrf value=fixture><input type=hidden name=issubmit value=0><table id=table1><tr><td>教学内容</td><td><label><input type=radio name=q1 value=A>很符合</label><label><input type=radio name=q1 value=B>较符合</label></td></tr><tr><td>总体评价</td><td><label><input type=radio name=q2 value=A>很符合</label><label><input type=radio name=q2 value=B>较符合</label></td></tr></table></form>"
        return org.jsoup.Jsoup.parse(html,url)
      }
      override fun post(url: String,fields: List<Pair<String,String>>,charset: String): org.jsoup.nodes.Document {
        check(url.endsWith("xspj_save.do?course=fixture"))
        check(fields.toMap()["q1"]=="A" && fields.toMap()["q2"]=="B" && fields.toMap()["csrf"]=="fixture")
        posts++;completed=true;return org.jsoup.Jsoup.parse("保存成功",url)
      }
    }
    val records=mutableSetOf<String>()
    val journal=object: EvaluationJournal {
      override fun uncertain(key: String)=key in records
      override fun markUncertain(key: String){records.add(key)}
      override fun confirmed(key: String){records.remove(key)}
    }
    val engine=TeachingEvaluationEngine(transport,journal,{})
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"evaluation")}
    ui {
      val delegate=CampusToolsActivity::class.java.getDeclaredField("evaluation\$delegate").apply { isAccessible=true }
      delegate.set(activity,lazyOf(engine))
    }
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(view,"!!document.getElementById('scanEvaluation')")
    js(view,"document.getElementById('scanEvaluation').click()")
    waitJs(view,"!document.getElementById('submitEvaluation').disabled")
    check(posts==0)
    fun clickNative(text: String) {
      val until=SystemClock.uptimeMillis()+6000
      while(SystemClock.uptimeMillis()<until) {
        val node=uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.isClickable }
        if(node!=null) { check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); return }
        SystemClock.sleep(100)
      }
      error("Native confirmation button missing: $text")
    }
    ui { view.evaluateJavascript("document.getElementById('submitEvaluation').click()",null) }
    clickNative("取消")
    waitJs(view,"!evaluationBusy")
    check(posts==0)
    ui { view.evaluateJavascript("document.getElementById('submitEvaluation').click()",null) }
    clickNative("确认提交 1 门")
    waitJs(view,"document.getElementById('evaluationResults').textContent.includes('校方已确认完成')")
    check(posts==1 && records.isEmpty())
    check(js(view,"document.getElementById('submitEvaluation').disabled")=="true")
    report("Evaluation device test passed: preview GET only, native cancel sends no POST, one confirmation sends one mock POST, result verified; zero school submissions")
    ui { activity.finish() }
  }

  /** Local typography, nested native headers and navigation; never triggers school queries. */
  private fun presentation() {
    val reference=launch(NotificationSettingsActivity::class.java)
    waitForIdleSync()
    val referenceHeight=ui { reference.findViewById<android.view.View>(R.id.toolbar).height }
    ui { reference.finish() }
    for(page in listOf("schedule","sites","library","courses","evaluation","toolbox","enrollment")) {
      val activity=launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,page) }
      val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
      waitJs(view,"typeof meta !== 'undefined' && meta.page === '$page' && document.getElementById('root').children.length > 0")
      check(js(view,"getComputedStyle(document.body).fontSize")=="\"13px\"")
      val toolbar=ui { activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar) }
      check(ui { toolbar.height }==referenceHeight) { "$page toolbar height differs from settings" }
      if(page=="schedule") {
        waitJs(view,"!!document.getElementById('addSchedule')")
        js(view,"document.getElementById('addSchedule').click()")
        waitJs(view,"!!document.getElementById('scheduleForm')")
        waitForIdleSync()
        check(ui { toolbar.title.toString() }=="新建日程")
        ui { toolbar.getChildAt((0 until toolbar.childCount).first { toolbar.getChildAt(it) is android.widget.ImageButton }).performClick() }
        waitJs(view,"!!document.getElementById('addSchedule')")
        check(ui { toolbar.title.toString() }=="添加日程")
      }
      if(page=="library") {
        waitJs(view,"!!document.querySelector('[data-library-tab=favorites]')")
        js(view,"document.querySelector('[data-library-tab=favorites]').click()")
        waitForIdleSync();check(ui { toolbar.title.toString() }=="图书收藏")
      }
      if(page=="courses") {
        waitJs(view,"!!document.getElementById('teacherMode')")
        js(view,"document.getElementById('teacherMode').click()")
        waitForIdleSync();check(ui { toolbar.title.toString() }=="教师课表")
        ui { toolbar.subtitle="2026-2027-1" }
        waitForIdleSync();SystemClock.sleep(100)
        val delta=ui {
          val texts=(0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
          val title=texts.first { it.text.toString()=="教师课表" };val subtitle=texts.first { it.text.toString()=="2026-2027-1" }
          kotlin.math.abs((title.left+title.right)-(subtitle.left+subtitle.right))
        }
        check(delta<=2) { "Title/subtitle center mismatch: $delta px" }
      }
      report("$page: 13px body, matching toolbar height and nested title passed")
      ui { activity.finish() }
    }
    val prefs=targetContext.getSharedPreferences("classsche_prefs",android.content.Context.MODE_PRIVATE)
    val oldMode=prefs.getString("score_sync_mode",null)
    val refreshKey="selected_timetable_semester_refresh_requested"
    val hadRefresh=prefs.contains(refreshKey);val oldRefresh=prefs.getBoolean(refreshKey,false)
    prefs.edit().putString("score_sync_mode","disabled").putBoolean(refreshKey,false).commit()
    var main: Activity?=null
    try {
      val activity=launch(MainActivity::class.java);main=activity
      ui { MainActivity::class.java.getDeclaredMethod("showCachedScorePage").apply { isAccessible=true }.invoke(activity) }
      val score=ui { activity.findViewById<WebView>(R.id.contentWebView) }
      val toolbar=ui { activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar) }
      waitJs(score,"!!window.GeneralEducationPage")
      fun title(expected: String) {
        val deadline=SystemClock.uptimeMillis()+4000
        while(ui { toolbar.title.toString() }!=expected && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        check(ui { toolbar.title.toString() }==expected) { "Native score title expected $expected" }
      }
      fun back()=ui { toolbar.getChildAt((0 until toolbar.childCount).first { toolbar.getChildAt(it) is android.widget.ImageButton }).performClick() }
      js(score,"window.GeneralEducationPage.open()")
      title("学分统计")
      check(js(score,"getComputedStyle(document.querySelector('.general-web-toolbar')).display")=="\"none\"")
      check(js(score,"Array.from(document.querySelectorAll('#generalTotals span')).some(x=>x.textContent==='辅修')")=="true")
      ui { check(toolbar.menu.performIdentifierAction(toolbar.menu.getItem(0).itemId,0)) }
      waitJs(score,"window.GeneralEducationPage.state()==='classification'");title("学分统计")
      ui { check(toolbar.menu.performIdentifierAction(toolbar.menu.getItem(0).itemId,0)) }
      waitJs(score,"window.GeneralEducationPage.state()==='credits'");title("学分统计")
      js(score,"window.GeneralEducationPage.catalog()")
      title("课程分类表")
      js(score,"document.getElementById('generalCatalogSearch').value='00000052';document.getElementById('generalCatalogSearch').dispatchEvent(new Event('input'))")
      check(js(score,"document.querySelectorAll('#generalCatalogRows article').length")=="1")
      back();waitJs(score,"window.GeneralEducationPage.state()==='credits'");title("学分统计")
      back();waitJs(score,"window.GeneralEducationPage.state()==='scores'");title("成绩查询")
      report("Score: category totals, native edit/save/search headers, toolbar back through all levels passed")
    } finally {
      ui { main?.finish() }
      val editor=prefs.edit();if(oldMode==null)editor.remove("score_sync_mode") else editor.putString("score_sync_mode",oldMode)
      if(hadRefresh)editor.putBoolean(refreshKey,oldRefresh) else editor.remove(refreshKey)
      editor.commit()
      HeadlessScoreSyncScheduler.scheduleNext(targetContext)
    }
  }

  /** Actual Android WebView touch gestures on synthetic courses and isolated storage. */
  private fun roomsMulti() {
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"rooms")}
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!document.querySelector('[data-start=\"6\"]') || document.getElementById('status').textContent.length>0")
      check(js(view,"!!document.querySelector('[data-start=\"6\"]')")=="true") { "School filters unavailable: ${js(view,"document.getElementById('status').textContent")}" }
      js(view,"document.querySelector('[data-start=\"6\"]').click()")
      check(js(view,"document.querySelectorAll('#periodChips .active').length")=="2")
      snapshot("rooms-selection")
      js(view,"document.getElementById('queryRooms').click()")
      waitJs(view,"!!document.querySelector('#roomResults .summary') || document.getElementById('status').textContent.length>0",90000)
      check(js(view,"!!document.querySelector('#roomResults .summary')")=="true") { "School query failed: ${js(view,"document.getElementById('status').textContent")}" }
      check(js(view,"document.querySelectorAll('.room-name small').length")=="0")
      check(js(view,"document.querySelector('#roomResults .summary').textContent.includes('1—3、6—7节均空闲')")=="true")
      check(js(view,"(()=>{const r=[...document.querySelectorAll('.room-name')].map(x=>teachingBuildingRank(x.textContent));return r.every((v,i)=>i===0||r[i-1]<=v)})()")=="true")
      snapshot("rooms-result")
      report("Live Android multi-period classroom query passed: " + js(view,"document.querySelector('#roomResults .summary').textContent"))
    } finally { ui { activity.finish() } }
  }

  private fun libraryNativeForm() {
    val activity=launch(LibraryLoginActivity::class.java)
    val web=ui{activity.findViewById<WebView>(R.id.libraryLoginWebView)}
    try {
      val bitmap=android.graphics.Bitmap.createBitmap(90,34,android.graphics.Bitmap.Config.ARGB_8888)
      android.graphics.Canvas(bitmap).apply { drawColor(android.graphics.Color.WHITE);drawText("TEST",8f,25f,android.graphics.Paint().apply{color=android.graphics.Color.BLUE;textSize=22f}) }
      val stream=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);bitmap.recycle();val image=stream.toByteArray()
      val form="<html><form method='POST' action='redr_verify.php'><input name='number'><input name='passwd' type='password'><input name='captcha'><input name='select' type='radio' value='bar_no'><input type='submit'></form><img src='captcha.php'></html>"
      ui {
        web.stopLoading();val production=web.webViewClient
        web.webViewClient=object:android.webkit.WebViewClient(){
          override fun shouldInterceptRequest(v:WebView,r:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse =
            if(r.url.path.orEmpty().endsWith("captcha.php"))android.webkit.WebResourceResponse("image/png",null,java.io.ByteArrayInputStream(image))
            else android.webkit.WebResourceResponse("text/html","utf-8",java.io.ByteArrayInputStream(form.toByteArray()))
          override fun onPageStarted(v:WebView,url:String,icon:android.graphics.Bitmap?){production.onPageStarted(v,url,icon)}
          override fun onPageFinished(v:WebView,url:String){production.onPageFinished(v,url)}
        }
        web.loadUrl(CampusDataClient.LIBRARY+"reader/login.php")
      }
      val deadline=SystemClock.uptimeMillis()+15000
      while(SystemClock.uptimeMillis()<deadline && !ui{activity.findViewById<android.widget.Button>(R.id.librarySubmit).isEnabled})SystemClock.sleep(100)
      check(ui{activity.findViewById<android.widget.Button>(R.id.librarySubmit).isEnabled}) { "Native CAPTCHA image was not ready" }
      check(ui{activity.findViewById<android.widget.ImageView>(R.id.libraryCaptchaImage).drawable!=null})
      check(!ui{activity.findViewById<android.widget.EditText>(R.id.libraryPassword).isSaveEnabled})
      check(ui{activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.libraryPasswordLayout).placeholderText.toString()}=="默认密码为身份证后六位")
      check(ui{activity.findViewById<android.widget.Spinner>(R.id.libraryLoginType).selectedItemPosition}==0)
      check((ui{activity.window.attributes.flags} and android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)
      report("Native library login form passed: synthetic CAPTCHA copied to local ImageView, account/password/code inputs ready, barcode type default, no field persistence or screenshots; no real credentials submitted")
    } finally {ui{activity.finish()}}
  }

  /** Synthetic credentials only; uses an isolated Keystore alias and no-backup file. */
  private fun libraryCredentials() {
    val namespace="library_credentials_test"
    val vault=LibraryCredentialStore(targetContext,namespace)
    val old=LibraryCredentialStore.Credentials("synthetic-old-user","synthetic-old-pass","bar_no")
    val newer=LibraryCredentialStore.Credentials("synthetic-new-user","synthetic-new-pass","email")
    val file=java.io.File(targetContext.noBackupFilesDir,"$namespace.enc")
    var activity:Activity?=null
    vault.clear()
    try {
      check(vault.save(old));check(vault.load()==old)
      check(!vault.save(old.copy(password="")));check(vault.load()==old)
      fun checkCiphertext(){val raw=file.readText();check(listOf(old.username,old.password,newer.username,newer.password).none { raw.contains(it) });check(raw.startsWith("v1:"))}
      checkCiphertext()
      val screen=launch(LibraryLoginActivity::class.java);activity=screen
      val web=ui{screen.findViewById<WebView>(R.id.libraryLoginWebView)}
      fun attachStore(screen:Activity){
        LibraryLoginActivity::class.java.getDeclaredField("credentialStore").apply{isAccessible=true}.set(screen,vault)
        ui{LibraryLoginActivity::class.java.getDeclaredMethod("restoreCredentials").apply{isAccessible=true}.invoke(screen)}
      }
      attachStore(screen)
      check(ui{screen.findViewById<android.widget.EditText>(R.id.libraryUsername).text.toString()}==old.username)
      check(ui{screen.findViewById<android.widget.EditText>(R.id.libraryPassword).text.toString()}==old.password)
      val bitmap=android.graphics.Bitmap.createBitmap(90,34,android.graphics.Bitmap.Config.ARGB_8888)
      android.graphics.Canvas(bitmap).apply{drawColor(android.graphics.Color.WHITE);drawText("TEST",8f,25f,android.graphics.Paint().apply{color=android.graphics.Color.BLUE;textSize=22f})}
      val stream=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);bitmap.recycle();val image=stream.toByteArray()
      val accept=java.util.concurrent.atomic.AtomicBoolean(false);val posts=java.util.concurrent.atomic.AtomicInteger(0)
      val form="<html><form method='POST' action='redr_verify.php'><input name='number'><input name='passwd' type='password'><input name='captcha'><input name='select' type='radio' value='bar_no'><input name='select' type='radio' value='cert_no'><input name='select' type='radio' value='email'><input type='submit'></form><img src='captcha.php'></html>"
      ui {
        web.stopLoading();val production=web.webViewClient
        web.webViewClient=object:android.webkit.WebViewClient(){
          override fun shouldInterceptRequest(v:WebView,r:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse {
            if(r.url.path.orEmpty().endsWith("captcha.php"))return android.webkit.WebResourceResponse("image/png",null,java.io.ByteArrayInputStream(image))
            val html=if(r.method=="POST"&&r.url.path.orEmpty().endsWith("redr_verify.php")){
              posts.incrementAndGet();if(accept.get())"<html><a href='logout.php'>注销</a></html>" else "<html><script>alert('用户名密码错误')</script></html>"
            }else form
            return android.webkit.WebResourceResponse("text/html","utf-8",java.io.ByteArrayInputStream(html.toByteArray()))
          }
          override fun onPageStarted(v:WebView,url:String,icon:android.graphics.Bitmap?){production.onPageStarted(v,url,icon)}
          override fun onPageFinished(v:WebView,url:String){production.onPageFinished(v,url)}
        }
        web.loadUrl(CampusDataClient.LIBRARY+"reader/login.php")
      }
      fun awaitReady(){val deadline=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<deadline&&!ui{screen.findViewById<android.widget.Button>(R.id.librarySubmit).isEnabled})SystemClock.sleep(100);check(ui{screen.findViewById<android.widget.Button>(R.id.librarySubmit).isEnabled})}
      fun submit(){ui{
        screen.findViewById<android.widget.EditText>(R.id.libraryUsername).setText(newer.username)
        screen.findViewById<android.widget.EditText>(R.id.libraryPassword).setText(newer.password)
        screen.findViewById<android.widget.EditText>(R.id.libraryCaptcha).setText("TEST")
        screen.findViewById<android.widget.Spinner>(R.id.libraryLoginType).setSelection(2)
        screen.findViewById<android.widget.Button>(R.id.librarySubmit).performClick()
      }}
      awaitReady();submit()
      val failedDeadline=SystemClock.uptimeMillis()+15000
      while(SystemClock.uptimeMillis()<failedDeadline&&(posts.get()<1||!ui{screen.findViewById<android.widget.Button>(R.id.librarySubmit).isEnabled}))SystemClock.sleep(100)
      check(posts.get()==1);awaitReady();check(vault.load()==old)
      accept.set(true);submit()
      val deadline=SystemClock.uptimeMillis()+15000
      while(SystemClock.uptimeMillis()<deadline&&!ui{screen.isFinishing})SystemClock.sleep(100)
      check(ui{screen.isFinishing});check(posts.get()==2);check(vault.load()==newer);checkCiphertext()
      check(LibraryCredentialStore(targetContext,namespace).load()==newer)
      val reopened=launch(LibraryLoginActivity::class.java);activity=reopened
      ui{reopened.findViewById<WebView>(R.id.libraryLoginWebView).stopLoading()};attachStore(reopened)
      check(ui{reopened.findViewById<android.widget.EditText>(R.id.libraryUsername).text.toString()}==newer.username)
      check(ui{reopened.findViewById<android.widget.EditText>(R.id.libraryPassword).text.toString()}==newer.password)
      check(ui{reopened.findViewById<android.widget.Spinner>(R.id.libraryLoginType).selectedItemPosition}==2)
      // Reusing an already-authenticated page must not overwrite credentials with unsubmitted edits.
      ui{
        reopened.findViewById<android.widget.EditText>(R.id.libraryPassword).setText("synthetic-unsubmitted")
        LibraryLoginActivity::class.java.getDeclaredMethod("completeLogin").apply{isAccessible=true}.invoke(reopened)
      }
      check(vault.load()==newer)
      report("Library encrypted credential flow passed with synthetic data: failed login does not overwrite, authenticated POST saves, isolated no-backup ciphertext contains no plaintext, reopen prefills account/password/type, session reuse does not save unsubmitted edits; no real credentials or external POST used")
    } finally {ui{activity?.finish()};vault.clear();check(!file.exists())}
  }

  /** Interactive CAPTCHA is completed by the owner; never snapshot/log real reader data. */
  private fun libraryReal() {
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"library")}
    val view=ui{activity.findViewById<WebView>(R.id.toolsWebView)}
    var child:Activity?=null
    try {
      waitJs(view,"!!document.querySelector('[data-library-tab=account]')")
      js(view,"document.querySelector('[data-library-tab=account]').click()")
      waitJs(view,"document.querySelectorAll('.library-account-card').length===4 || document.getElementById('libraryAccountStatus').className==='hint'")
      if(js(view,"document.querySelectorAll('.library-account-card').length")=="0"){
        val monitor=addMonitor(LibraryLoginActivity::class.java.name,null,false)
        js(view,"document.getElementById('libraryLogin').click()")
        child=waitForMonitorWithTimeout(monitor,6000) ?: error("Login did not open")
        removeMonitor(monitor)
        val login=child ?: error("Native login missing")
        // Test APK only: pass fields in RAM over an ADB-forwarded localhost pipe, never via shell args/files.
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout=120000
        report("LOCAL_CREDENTIAL_PORT="+server.localPort)
        server.use { listener -> listener.accept().use { socket ->
          socket.soTimeout=15000
          val input=socket.getInputStream().bufferedReader(Charsets.UTF_8)
          val line=StringBuilder()
          while(line.length<8192){val c=input.read();if(c<0||c==10)break;line.append(c.toChar())}
          val fields=JSONObject(line.toString())
          ui {
            login.findViewById<android.widget.EditText>(R.id.libraryUsername).setText(fields.getString("username"))
            login.findViewById<android.widget.EditText>(R.id.libraryPassword).setText(fields.getString("password"))
            login.findViewById<android.widget.EditText>(R.id.libraryCaptcha).requestFocus()
          }
          socket.getOutputStream().write("OK\n".toByteArray());socket.getOutputStream().flush()
        } }
        report("Ready for owner CAPTCHA in native login. No credentials or reader values are logged.")
        waitJs(view,"document.querySelectorAll('.library-account-card').length===4",600000)
      }
      check(js(view,"[...document.querySelectorAll('.library-account-card strong')].every(x=>x.textContent.length>0)")=="true")
      report("Real library account: native read-only API returned exactly four nonempty status tiles; values were not logged or saved.")
      val monitor=addMonitor(CampusBrowserActivity::class.java.name,null,false)
      js(view,"document.getElementById('openLibraryHome').click()")
      val more=waitForMonitorWithTimeout(monitor,6000) ?: error("Reader webpage did not open")
      child=more;removeMonitor(monitor)
      val web=ui{more.findViewById<WebView>(R.id.toolsWebView)}
      waitJs(web,"Array.from(document.querySelectorAll('a[href]')).some(a=>/logout/.test(a.getAttribute('href')||'')&&/注销|退出/.test(a.textContent))")
      check(ui{web.settings.cacheMode}==android.webkit.WebSettings.LOAD_NO_CACHE)
      report("Real library webpage: existing WebView login session reused; authenticated page opened without another login. No screenshots or reader content saved.")
    } finally {ui{child?.finish();activity.finish()}}
  }

  private fun libraryAccount() {
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"library")}
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!document.querySelector('[data-library-tab=account]')")
      js(view,"window.originalLibraryRequest=request;request=async function(action,...args){if(action==='library.status')return {cards:[1,2,3,4].map(i=>({label:'模拟状态'+i,value:String(i)}))};return window.originalLibraryRequest(action,...args)};document.querySelector('[data-library-tab=account]').click()")
      waitJs(view,"document.querySelectorAll('.library-account-card').length===4")
      check(js(view,"document.querySelectorAll('#libraryContent h2').length===0 && !document.getElementById('libraryContent').textContent.includes('独立登录') && !!document.querySelector('#refreshLibraryStatus svg')")=="true")
      snapshot("library-account-synthetic")
      val monitor=addMonitor(LibraryLoginActivity::class.java.name,null,false)
      js(view,"document.getElementById('libraryLogin').click()")
      val login=waitForMonitorWithTimeout(monitor,6000) ?: error("Independent library login did not open")
      removeMonitor(monitor)
      val web=ui {login.findViewById<WebView>(R.id.libraryLoginWebView)}
      check((ui{login.window.attributes.flags} and android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0)
      check(ui{web.settings.cacheMode}==android.webkit.WebSettings.LOAD_NO_CACHE)
      check(!ui{web.settings.domStorageEnabled})
      ui{login.finish()}
      waitJs(view,"document.querySelectorAll('.library-account-card').length===4")
      val more=addMonitor(CampusBrowserActivity::class.java.name,null,false)
      js(view,"document.getElementById('openLibraryHome').click()")
      val browser=waitForMonitorWithTimeout(more,6000) ?: error("Library webpage did not open")
      removeMonitor(more)
      check(browser.intent.getStringExtra("url")==CampusDataClient.LIBRARY+"reader/redr_info.php")
      check(browser.intent.getBooleanExtra("private_library",false))
      ui{browser.finish()}
      report("Library account UI passed with synthetic four tiles: independent login, memory-only page, private shared-WebView webpage route; no real credentials or reader data used")
    } finally {ui{activity.finish()}}
  }

  private fun libraryNew() {
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"library")}
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!document.getElementById('bookSearch')")
      check(js(view,"document.getElementById('bookSearch').getBoundingClientRect().top===document.querySelector('[aria-label=图书功能]').getBoundingClientRect().bottom")=="true")
      js(view,"document.getElementById('bookKeyword').value='活着';document.getElementById('bookSearch').requestSubmit()")
      waitJs(view,"!!document.querySelector('[data-book]') || document.getElementById('status').textContent.length>0")
      check(js(view,"!!document.querySelector('[data-book]')")=="true") { "Library search failed: ${js(view,"document.getElementById('status').textContent")}" }
      snapshot("library")
      report("Live Android OPAC search succeeded: " + js(view,"document.querySelector('.summary').textContent"))
      js(view,"document.querySelector('[data-book]').click()")
      waitJs(view,"!!document.querySelector('.holding') || document.getElementById('status').textContent.length>0")
      check(js(view,"!!document.querySelector('.holding')")=="true") { "Holdings failed: ${js(view,"document.getElementById('status').textContent")}" }
      check(js(view,"document.querySelectorAll('[data-library-tab=loans]').length")=="0")
      report("Library search and holdings use compact cards; removed unavailable loans entry")
    } finally { ui { activity.finish() } }
  }

  private fun examExpansion() {
    val activity=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"enrollment")}
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!window.CampusUI")
      val template=targetContext.assets.open("exam-view.html").bufferedReader().use{it.readText()}
      val today=java.time.LocalDate.now()
      fun exam(day:java.time.LocalDate,name:String)=ExamArrangement(1,"40001","TEST",name,"$day 09:00~11:00","模拟地点","18","","")
      fun load(data:List<ExamArrangement>){
        val start=template.indexOf("const exams = ");val end=template.indexOf(';',start)
        val html=template.replaceRange(start,end+1,"const exams = ${ExamRenderer.toJson(data)};")
        ui { view.loadDataWithBaseURL("https://classsche.local/exam-expansion-synthetic/",html,"text/html","utf-8",null) }
        waitJs(view,"document.querySelectorAll('.exam-card').length===${data.size}")
      }
      load(listOf(exam(today.plusDays(4),"较远"),exam(today.minusDays(1),"过去"),exam(today.plusDays(1),"最近")))
      check(js(view,"document.querySelectorAll('.exam-card.active').length")=="1")
      check(js(view,"document.querySelector('.exam-card.active').dataset.index")=="\"2\"")
      snapshot("upcoming-exam")
      load(listOf(exam(today.minusDays(2),"过去甲"),exam(today.minusDays(1),"过去乙")))
      check(js(view,"document.querySelectorAll('.exam-card.active').length")=="0")
      report("Exam expansion passed in Android WebView: unsorted nearest future only, past exams collapsed; all past means none expanded")
    } finally {ui {activity.finish()}}
  }

  private fun homeToolbar() {
    val prefs=targetContext.getSharedPreferences("classsche_prefs",android.content.Context.MODE_PRIVATE)
    val old=prefs.getString("score_sync_mode",null);prefs.edit().putString("score_sync_mode","disabled").commit()
    var main:Activity?=null
    try {
      val activity=launch(MainActivity::class.java);main=activity;waitForIdleSync();SystemClock.sleep(500)
      val toolbar=ui { activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar) }
      check(ui{toolbar.minimumHeight}==0)
      val dp=targetContext.resources.displayMetrics.density
      check(ui{toolbar.paddingBottom}==(4*dp).toInt())
      report("Home toolbar compact layout passed: height="+ui{toolbar.height}+"px, bottom padding="+ui{toolbar.paddingBottom}+"px; no extra 10dp")
        val grid=ui{activity.findViewById<android.widget.GridLayout>(R.id.homeMenuGrid)}
        val labels=ui{(0 until grid.childCount).map{(grid.getChildAt(it) as android.widget.LinearLayout).getChildAt(1) as android.widget.TextView}.map{it.text.toString()}}
        check("邮件管理" in labels&&"空闲教室" !in labels)
        val blocked=addMonitor(SchoolMailActivity::class.java.name,ActivityResult(Activity.RESULT_CANCELED,null),true)
        ui{grid.getChildAt(labels.indexOf("邮件管理")).performClick()};waitForIdleSync()
        check(blocked.hits==1);removeMonitor(blocked)
        val monitor=addMonitor(SchoolMailSettingsActivity::class.java.name,null,false)
        ui{MainActivity::class.java.getDeclaredMethod("showProfilePage").apply{isAccessible=true}.invoke(activity);activity.findViewById<android.view.View>(R.id.profileMailSettingsRow).performClick()}
        val settings=waitForMonitorWithTimeout(monitor,5000)?:error("Profile mail update settings did not open");removeMonitor(monitor);ui{settings.finish()}
        ui{MainActivity::class.java.getDeclaredMethod("showCachedTimetable").apply{isAccessible=true}.invoke(activity)}
        waitForIdleSync()
        val selector=ui{toolbar.menu.findItem(0x4C530002)?.actionView}?:error("Missing timetable semester dropdown")
        check(ui{selector.contentDescription.toString().startsWith("选择课表学期")})
        check(ui{selector.background==null&&(selector as android.widget.TextView).currentTextColor==android.graphics.Color.WHITE})
        ui{selector.performClick()};waitForIdleSync()
        var popupVisible=false;val popupDeadline=SystemClock.uptimeMillis()+5000
        while(!popupVisible&&SystemClock.uptimeMillis()<popupDeadline){popupVisible=uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("跟随当前学期")?.isNotEmpty()==true;if(!popupVisible)SystemClock.sleep(100)}
        check(popupVisible){"Semester popup did not become visible"}
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        report("Home mail entry replaces rooms; profile opens shared mail settings; timetable native semester dropdown opens without a network query")
    } finally {
      ui { main?.finish() }
      val editor=prefs.edit();if(old==null)editor.remove("score_sync_mode") else editor.putString("score_sync_mode",old);editor.commit()
    }
  }

  private fun refinement() {
    val schedule=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"schedule")}
    val view=ui { schedule.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!document.getElementById('addSchedule')")
      js(view,"document.getElementById('addSchedule').click();document.getElementById('selectAllWeeks').click()")
      check(js(view,"document.querySelectorAll('#weekPicks .active').length")=="20")
      check(js(view,"document.querySelector('[name=weeksText]').value")=="\"1-20\"")
      js(view,"document.getElementById('selectAllWeeks').scrollIntoView({block:'center'})")
      snapshot("weeks")
      report("Native schedule editor: select all 20 weeks passed; no schedule saved")
    } finally { ui { schedule.finish() } }
    val exam=ExamArrangement(1,"40001","TEST","模拟考试","2026-10-30 10:20~12:00","模拟教室","18","","")
    val occurrence=ExamOccurrence(exam,java.time.LocalDate.of(2026,10,30),java.time.LocalTime.of(10,20),java.time.LocalTime.of(12,0))
    val overlay=ExamTimetableOverlay.courses(listOf(occurrence,occurrence),java.time.LocalDate.of(2026,8,24))
    check(overlay.size==1 && overlay[0].weeks=="10周" && overlay[0].weekday=="星期五" && overlay[0].seatNumber=="18" && overlay[0].notes.isEmpty())
    check(ExamTimetableOverlay.courses(listOf(occurrence),null).isEmpty())
    val html=TimetableRenderer.toHtml(targetContext,overlay,"2026-2027-1")
    check(!html.contains("40001"))
    check(html.contains("【考试】模拟考试") && html.contains("10:20") && html.contains("seatNumber") && html.contains("18"))
    check(ExamTimetableOverlay.courses(listOf(occurrence),java.time.LocalDate.of(2027,1,1)).isEmpty())
    report("Exam timetable: date/week/day, original time, seat, duplicate filtering and unknown calendar passed")
    val browser=launch(CampusBrowserActivity::class.java){putExtra("url","https://example.org/")}
    val web=ui { browser.findViewById<WebView>(R.id.toolsWebView) }
    try {
      ui { web.stopLoading();web.loadDataWithBaseURL("https://classsche.local/exam-synthetic/",html,"text/html","utf-8",null) }
      waitJs(web,"!!document.querySelector('.course-card')")
      SystemClock.sleep(650) // Allow the first native WebView viewport resize to settle before clicking.
      js(web,"document.querySelector('.course-card').click()")
      waitJs(web,"document.getElementById('detailSheet').classList.contains('show')")
      SystemClock.sleep(400)
      check(js(web,"(()=>{const r=document.getElementById('seatNumberRow').getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight})()")=="true") { "Exam seat is not visible in the sheet" }
      check(js(web,"document.getElementById('sheetPeriodLine').textContent")=="\"10:20-12:00\"")
      check(js(web,"document.getElementById('sheetSeatNumber').textContent==='18'")=="true")
      snapshot("exam")
      report("Native WebView exam card and details show real time and seat")
      ui {
        web.webViewClient=object:android.webkit.WebViewClient(){
          override fun shouldInterceptRequest(v:WebView,r:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse = android.webkit.WebResourceResponse("text/html","utf-8",java.io.ByteArrayInputStream("<html><body style='width:1800px;height:1800px'>Synthetic zoom/copy test</body></html>".toByteArray()))
        }
        web.loadUrl("https://example.org/current?test=1")
      }
      waitJs(web,"document.body.textContent.includes('Synthetic zoom/copy test')")
      check(ui { web.settings.supportZoom() && web.settings.builtInZoomControls && !web.settings.displayZoomControls })
      SystemClock.sleep(400)
      val before=ui { web.scale }
      val down=SystemClock.uptimeMillis()
      val centerX=ui { web.width/2f };val centerY=ui { web.height/2f }
      fun fingers(action: Int, distance: Float, count: Int) = ui {
        val props=Array(count){i->android.view.MotionEvent.PointerProperties().apply{id=i;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(count){i->android.view.MotionEvent.PointerCoords().apply{x=centerX+(if(i==0)-distance else distance);y=centerY;pressure=1f;size=1f}}
        val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        web.dispatchTouchEvent(event);event.recycle()
      }
      fingers(android.view.MotionEvent.ACTION_DOWN,40f,1)
      fingers(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f,2)
      for(i in 1..12){fingers(android.view.MotionEvent.ACTION_MOVE,40f+i*9,2);SystemClock.sleep(25)}
      fingers(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),148f,2)
      fingers(android.view.MotionEvent.ACTION_UP,148f,1);SystemClock.sleep(500)
      check(ui { web.scale }>before) { "Two-finger zoom did not change the scale" }
      val toolbar=ui { browser.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar) }
      ui { toolbar.menu.performIdentifierAction(toolbar.menu.getItem(0).itemId,0) }
      val clip=ui { (browser.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text.toString() }
      check(clip=="https://example.org/current?test=1") { "Copied URL mismatch: current=" + ui { web.url }.takeIf{it.orEmpty().startsWith("https://example.org/")||it.orEmpty().startsWith("https://classsche.local/")} + "; clipboardLength=" + clip.length }
      report("External browser: zoom enabled and scale changes; toolbar copies the current link, not the initial URL")
    } finally { ui { browser.finish() } }
    creditDrag()
  }

  private fun creditDrag() {
    val activity=launch(CampusToolsActivity::class.java) { putExtra(CampusToolsActivity.EXTRA_PAGE,"enrollment") }
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    try {
      waitJs(view,"!!document.getElementById('enrollmentKeyword')")
      val template=targetContext.assets.open("score-view.html").bufferedReader().use { it.readText() }
      val data="""[{"courseCode":"00000001","courseName":"模拟课程","credits":"2","score":"80","courseNature":"计划外"}]"""
      val start=template.indexOf("const rawScores = ")
      check(start>=0)
      val html=template.replaceRange(start,template.indexOf(';',start)+1,"const rawScores = $data;")
        .replace("classsche.general-education.categories.v1","classsche.general-education.drag-device-test")
      fun load(){ui { view.loadDataWithBaseURL("https://classsche.local/credit-drag/",html,"text/html","utf-8","https://classsche.local/credit-drag/") };waitJs(view,"!!window.GeneralEducationPage && !document.getElementById('scoreOverview').hidden")}
      load()
      js(view,"localStorage.removeItem('classsche.general-education.drag-device-test');window.GeneralEducationPage.updateCatalog({imported:true,categories:['人文素养类','艺术审美类'],courses:[{courseCode:'00000001',courseName:'模拟课程',category:'人文素养类'}]});window.GeneralEducationPage.open();window.GeneralEducationPage.edit()")
      snapshot("classification")
      check(js(view,"document.getElementById('generalStatsView').hidden")=="false")
      check(js(view,"document.querySelectorAll('[data-course-key] select').length")=="0")
      fun point(selector: String, scroll: Boolean = true): Pair<Float,Float> {
        if(scroll)js(view,"document.querySelector(${JSONObject.quote(selector)}).scrollIntoView({block:'center'})")
        val drawn=CountDownLatch(1)
        ui { view.postVisualStateCallback(1,object: WebView.VisualStateCallback(){override fun onComplete(id: Long){drawn.countDown()}}) }
        check(drawn.await(5,TimeUnit.SECONDS))
        val raw=js(view,"JSON.stringify((()=>{const n=document.querySelector(${JSONObject.quote(selector)});const r=n.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+Math.min(20,r.height/2),width:innerWidth};})())")
        val decoded=org.json.JSONTokener(raw).nextValue() as String
        val pos=JSONObject(decoded);val scale=ui { view.width }.toDouble()/pos.getDouble("width")
        return (pos.getDouble("x")*scale).toFloat() to (pos.getDouble("y")*scale).toFloat()
      }
      var down=0L
      fun touch(action: Int,pos: Pair<Float,Float>) {
        if(action==android.view.MotionEvent.ACTION_DOWN)down=SystemClock.uptimeMillis()
        ui {
          val properties=arrayOf(android.view.MotionEvent.PointerProperties().apply{id=0;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER})
          val coords=arrayOf(android.view.MotionEvent.PointerCoords().apply{x=pos.first;y=pos.second;pressure=1f;size=1f})
          val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
          view.dispatchTouchEvent(event);event.recycle()
        }
      }
      js(view,"window.syntheticDragEvents=[];['touchstart','touchmove','touchend','touchcancel'].forEach(type=>document.addEventListener(type,e=>{const t=e.changedTouches[0];window.syntheticDragEvents.push({type,x:t?.clientX,y:t?.clientY,target:e.target.tagName,scroll:scrollY});window.syntheticDragEvents=window.syntheticDragEvents.slice(-8);},{capture:true,passive:true}))")
      val card="[data-course-key='code:00000001']"
      var pos=point("$card h3")
      touch(android.view.MotionEvent.ACTION_DOWN,pos);SystemClock.sleep(550)
      waitJs(view,"!!document.querySelector('.general-drag-ghost')")
      val fromArt=pos;pos=point("[data-category-drop='艺术审美类'] h2",false)
      for(step in 1..8){touch(android.view.MotionEvent.ACTION_MOVE,(fromArt.first+(pos.first-fromArt.first)*step/8) to (fromArt.second+(pos.second-fromArt.second)*step/8));SystemClock.sleep(30)}
      report("Synthetic drag coordinates: "+js(view,"JSON.stringify({events:window.syntheticDragEvents,ghost:!!document.querySelector('.general-drag-ghost'),active:document.querySelector('.general-drop-active')?.dataset.categoryDrop,target:document.querySelector('[data-category-drop=艺术审美类] h2').getBoundingClientRect().toJSON(),width:innerWidth,height:innerHeight})"))
      waitJs(view,"!!document.querySelector('[data-category-drop=艺术审美类].general-drop-active')")
      touch(android.view.MotionEvent.ACTION_UP,pos)
      waitJs(view,"!!document.querySelector('[data-category-drop=艺术审美类] [data-course-key]')")
      SystemClock.sleep(400) // Two instantly synthesized gestures can be consumed as a native double-tap.
      pos=point("$card .general-drag-handle")
      touch(android.view.MotionEvent.ACTION_DOWN,pos)
      waitJs(view,"!!document.querySelector('.general-drag-ghost')")
      val fromMinor=pos;pos=point("[data-category-drop='辅修'] h2",false)
      for(step in 1..8){touch(android.view.MotionEvent.ACTION_MOVE,(fromMinor.first+(pos.first-fromMinor.first)*step/8) to (fromMinor.second+(pos.second-fromMinor.second)*step/8));SystemClock.sleep(30)}
      waitJs(view,"!!document.querySelector('[data-category-drop=辅修].general-drop-active')")
      touch(android.view.MotionEvent.ACTION_UP,pos)
      waitJs(view,"!!document.querySelector('[data-category-drop=辅修] [data-course-key]')")
      js(view,"window.GeneralEducationPage.toolbarAction()")
      check(js(view,"JSON.parse(localStorage.getItem('classsche.general-education.drag-device-test'))['code:00000001']")=="\"辅修\"")
      load();js(view,"window.GeneralEducationPage.open()")
      check(js(view,"document.getElementById('generalCategoryList').textContent.includes('辅修')")=="true")
      js(view,"window.GeneralEducationPage.edit();document.querySelector('[data-general-remove]').click()")
      check(js(view,"document.querySelector('[data-course-key] select').value")=="\"__pending__\"")
      js(view,"document.querySelector('[data-course-key] select').scrollIntoView({block:'center'})")
      snapshot("pending")
      report("Android touch drag passed: long-press card, immediate handle, cross-category movement, saved mapping retained after reload; synthetic data only")
    } finally {
      runCatching { js(view,"localStorage.removeItem('classsche.general-education.drag-device-test')") }
      ui { activity.finish() }
    }
  }

  /** Local catalogue, real navigation and the production submit gate; no school requests. */
  private fun enrollmentCatalogue() {
    check(CourseEnrollmentFeature.browseEnabled && !CourseEnrollmentFeature.enabled)
    val hub=launch(CampusToolsActivity::class.java){putExtra(CampusToolsActivity.EXTRA_PAGE,"toolbox")}
    val hubView=ui { hub.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(hubView,"!!document.querySelector('[data-open-tool=enrollment]')")
    val monitor=addMonitor(CampusToolsActivity::class.java.name,null,false)
    ui { hubView.evaluateJavascript("document.querySelector('[data-open-tool=enrollment]').click()",null) }
    val activity=waitForMonitorWithTimeout(monitor,6000) ?: error("Enrollment catalogue did not open")
    removeMonitor(monitor)
    val view=ui { activity.findViewById<WebView>(R.id.toolsWebView) }
    waitJs(view,"!!document.getElementById('enrollmentKeyword') && enrollmentItems.length > 0")
    val cached=GeneralEducationCatalogStore.read(targetContext)
    if(cached!=null)check(js(view,"generalCatalog.sourceSha256")==org.json.JSONObject.quote(cached.getString("sourceSha256"))) { "Mobile catalogue cache was not injected" }
    js(view,"document.getElementById('enrollmentKeyword').value='００００００１１';document.getElementById('enrollmentKeyword').dispatchEvent(new Event('input'))")
    waitJs(view,"enrollmentMatches.length === 1 && document.querySelector('.enrollment-course h3').textContent === '坦克学'")
    js(view,"document.getElementById('clearEnrollment').click();document.getElementById('enrollmentCategory').value='经济社会类（四史）';document.getElementById('enrollmentCategory').dispatchEvent(new Event('change'))")
    waitJs(view,"enrollmentMatches.length === 10")
    check(js(view,"document.querySelectorAll('.enrollment-course button:not(:disabled)').length === 0")=="true")
    js(view,"window.enrollmentGate='waiting';void request('enrollment.submit',{courseCode:'00000011'}).then(()=>window.enrollmentGate='unexpected-success').catch(error=>window.enrollmentGate=error.code)")
    waitJs(view,"window.enrollmentGate === 'FEATURE_DISABLED'")
    report("Enrollment catalogue passed: hub navigation, shared mobile cache, full-width code search, category filter; real enrollment gate rejects submit; zero school requests")
    ui { activity.finish() }
    val opening=CampusToolsActivity::class.java.getDeclaredField("openingTool").apply { isAccessible=true }
    val until=SystemClock.uptimeMillis()+3000
    while(ui { opening.getBoolean(hub) } && SystemClock.uptimeMillis()<until)SystemClock.sleep(100)
    check(!ui { opening.getBoolean(hub) }) { "Returning to toolbox did not reset navigation state" }
    ui { hub.finish() }
  }
}
