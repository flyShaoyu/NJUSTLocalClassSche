package com.classsche.mobile

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService

/** Keeps the desktop QR page polling in the existing authentication WebView. */
internal class IdentityQrLoginController(
  private val activity: Activity,
  private val webView: WebView,
  private val handler: Handler,
  private val executor: ExecutorService,
  private val onStatus: (String) -> Unit,
  private val requestGalleryPermission: () -> Unit,
  private val reloadPasswordLoginPage: () -> Unit,
  private val onPasswordFormReady: () -> Unit,
  private val onDiagnostic: (String, String) -> Unit,
  private val onActiveChanged: (Boolean) -> Unit
) {
  private val mobileUserAgent = webView.settings.userAgentString
  private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
    (Regex("Chrome/([\\d.]+)").find(mobileUserAgent)?.groupValues?.get(1) ?: "139.0.0.0") + " Safari/537.36"
  private var dialog: AlertDialog? = null
  private var imageView: ImageView? = null
  private var statusView: TextView? = null
  private var active = false
  private var generation = 0
  private var imageUrl: String? = null
  private var currentBitmap: Bitmap? = null
  private var savingImage = false
  private var pendingSaveGeneration: Int? = null
  private var lastStatus = ""
  private var pageReady = false
  private var pageState = "waiting"
  private var pageActivated = false
  private var inspecting = false
  private var inspectionSequence = 0L
  private var refreshPending = false
  private var waitingSince = 0L
  private var terminalError = false
  private var resourceFailure: String? = null
  private var pageReloadAttempted = false
  private var hostResumed = true
  private var resumePending = false
  private var authenticationStarted = false
  private var authenticationStartedAt = 0L
  private var authenticationProgressAt = 0L
  private var pausedAt = 0L
  private var authenticationStep = "提交二维码认证"
  private var lastServerStatus = ""
  private val authenticationWatchdog = object : Runnable {
    override fun run() {
      if (!active || !authenticationStarted || terminalError || !hostResumed) return
      val now = SystemClock.elapsedRealtime()
      if (now - authenticationProgressAt > 20_000L || now - authenticationStartedAt > 60_000L) {
        fail("微信已确认，但$authenticationStep 超时；请关闭弹窗后重试，已保存的登录状态会保留")
      } else handler.postDelayed(this, 1000L)
    }
  }
  private val poll = object : Runnable {
    override fun run() {
      if (!hostResumed || authenticationStarted || (!active && pageReady) || terminalError) return
      inspectPage()
      if ((active || !pageReady) && !terminalError) handler.postDelayed(this, 500L)
    }
  }

  fun start() {
    if (active) return
    active = true
    onActiveChanged(true)
    val content = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(16), dp(24), dp(16))
      setBackgroundColor(Color.WHITE)
    }
    imageView = ImageView(activity).apply {
      scaleType = ImageView.ScaleType.FIT_CENTER
      contentDescription = "学校统一认证微信登录二维码"
    }
    content.addView(imageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
    statusView = TextView(activity).apply {
      setTextColor(Color.rgb(67, 96, 127))
      textSize = 14f
      setPadding(0, dp(16), 0, 0)
    }
    content.addView(statusView)
    dialog = AlertDialog.Builder(activity)
      .setTitle("微信扫码登录")
      .setView(content)
      .setPositiveButton("重新获取", null)
      .setNeutralButton("保存到相册", null)
      .setNegativeButton("关闭", null)
      .create().also { popup ->
        popup.setOnDismissListener { cancel(reloadLoginPage = true) }
        popup.show()
        popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { refresh() }
        popup.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { saveCurrentImage() }
        updateSaveButton()
      }
    generation++
    inspecting = false
    refreshPending = false
    waitingSince = SystemClock.elapsedRealtime()
    terminalError = false
    pageReloadAttempted = false
    pageActivated = false
    pageState = "waiting"
    authenticationStarted = false
    lastServerStatus = ""
    showStatus("正在读取当前登录页的二维码…")
    if (!isIdentityLoginPage() || webView.settings.userAgentString != desktopUserAgent) {
      reloadQrPage()
    } else {
      inspectPage()
    }
    handler.removeCallbacks(poll)
    handler.postDelayed(poll, 500L)
  }

  fun onLoginPageReady() {
    if (!isIdentityLoginPage() || terminalError || authenticationStarted) return
    inspectPage()
    handler.removeCallbacks(poll)
    if (!pageReady || active) handler.postDelayed(poll, 500L)
  }

  fun onPageStarted(url: String) {
    handler.removeCallbacks(poll)
    generation++
    pageReady = false
    pageActivated = false
    refreshPending = false
    inspecting = false
    terminalError = false
    resourceFailure = null
    waitingSince = SystemClock.elapsedRealtime()
    clearImage()
    if (active && authenticationStarted) {
      authenticationProgressAt = SystemClock.elapsedRealtime()
      val uri = Uri.parse(url)
      authenticationStep = when (uri.host) {
        "ehall2.njust.edu.cn" -> "进入智慧理工服务门户"
        "bkjw.njust.edu.cn" -> if (uri.path.orEmpty().contains("xskb")) "加载教务课表" else "建立教务系统会话"
        else -> "完成统一身份认证"
      }
      showStatus("微信已确认，正在$authenticationStep…")
      onDiagnostic("INFO", "二维码认证跳转：${uri.host.orEmpty()}${uri.path.orEmpty()}")
      restartAuthenticationWatchdog()
    }
  }

  fun isAuthenticationSubmitted(): Boolean = active && authenticationStarted

  fun onHostPaused() {
    hostResumed = false
    pausedAt = SystemClock.elapsedRealtime()
    handler.removeCallbacks(poll)
    handler.removeCallbacks(authenticationWatchdog)
  }

  fun onHostResumed() {
    hostResumed = true
    if (authenticationStarted && pausedAt > 0L) {
      val pausedDuration = SystemClock.elapsedRealtime() - pausedAt
      authenticationStartedAt += pausedDuration
      authenticationProgressAt += pausedDuration
    }
    pausedAt = 0L
    if (!active || terminalError) return
    if (authenticationStarted) {
      restartAuthenticationWatchdog()
      return
    }
    // Recheck the same QR/cookie session immediately; never issue another token/password.
    inspectionSequence++
    inspecting = false
    resumePending = true
    handler.removeCallbacks(poll)
    inspectPage()
    handler.postDelayed(poll, 500L)
  }

  fun onPageError(message: String) {
    if (active) fail(message)
  }

  fun onResourceFailure(url: String, detail: String) {
    val uri = Uri.parse(url)
    if (uri.host != "ids.njust.edu.cn") return
    if (uri.path == "/authserver/qrCode/getStatus.htl" && (!hostResumed || authenticationStarted)) {
      if (!hostResumed) onDiagnostic("INFO", "后台扫码状态检查未完成，返回前台时重新检查原二维码")
      return
    }
    val step = when (uri.path) {
      "/authserver/qrCode/getToken" -> "申请二维码"
      "/authserver/qrCode/getCode" -> "加载二维码图片"
      "/authserver/qrCode/getStatus.htl" -> "检查扫码状态"
      else -> return
    }
    resourceFailure = "$step 失败：$detail，请点击“重新获取”"
    if (active) fail(resourceFailure!!)
  }

  fun onGalleryPermissionResult(granted: Boolean) {
    val requestedGeneration = pendingSaveGeneration ?: return
    pendingSaveGeneration = null
    updateSaveButton()
    if (!granted) {
      Toast.makeText(activity.applicationContext, "未授予存储权限，二维码未保存；可在系统设置中允许后重试", Toast.LENGTH_LONG).show()
      return
    }
    if (active && requestedGeneration == generation) saveCurrentImage()
    else Toast.makeText(activity.applicationContext, "二维码已关闭或刷新，请重新打开后保存", Toast.LENGTH_SHORT).show()
  }

  fun complete() = finish(reloadLoginPage = false)

  fun destroy() = finish(reloadLoginPage = false)

  fun useDesktopUserAgent() {
    if (webView.settings.userAgentString != desktopUserAgent) {
      webView.settings.userAgentString = desktopUserAgent
    }
  }

  private fun cancel(reloadLoginPage: Boolean) {
    if (!active) return
    finish(reloadLoginPage)
  }

  private fun finish(reloadLoginPage: Boolean) {
    active = false
    generation++
    handler.removeCallbacks(poll)
    handler.removeCallbacks(authenticationWatchdog)
    dialog?.setOnDismissListener(null)
    dialog?.dismiss()
    dialog = null
    imageView = null
    statusView = null
    imageUrl = null
    currentBitmap = null
    pendingSaveGeneration = null
    inspecting = false
    pageActivated = false
    refreshPending = false
    authenticationStarted = false
    resumePending = false
    onActiveChanged(false)
    if (reloadLoginPage) {
      webView.stopLoading()
      reloadPasswordLoginPage()
    }
  }

  private fun refresh() {
    if (authenticationStarted) return
    if (!terminalError && (refreshPending || pageState in setOf("pending_token", "pending_image", "loading"))) return
    generation++
    inspecting = false
    terminalError = false
    resourceFailure = null
    lastServerStatus = ""
    refreshPending = true
    pageReloadAttempted = false
    waitingSince = SystemClock.elapsedRealtime()
    clearImage()
    showStatus("正在通过学校网页重新获取二维码…")
    if (isIdentityLoginPage()) inspectPage() else reloadQrPage()
    handler.removeCallbacks(poll)
    handler.postDelayed(poll, 500L)
  }

  private fun inspectPage() {
    if (!hostResumed || inspecting || terminalError || authenticationStarted || !isIdentityLoginPage()) return
    inspecting = true
    val requestGeneration = generation
    val requestInspection = ++inspectionSequence
    val action = when {
      !active -> "prepare"
      refreshPending -> "refresh"
      resumePending -> "resume"
      !pageActivated -> "start"
      else -> "inspect"
    }
    webView.evaluateJavascript(IdentityQrPageScript.command(action, imageUrl.orEmpty())) { raw ->
      if (requestGeneration != generation || requestInspection != inspectionSequence) return@evaluateJavascript
      inspecting = false
      if (!hostResumed) return@evaluateJavascript
      val data = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
      if (data == null) {
        if (active) fail("二维码页面解析错误：未读到学校网页状态，请点击“重新获取”")
        else pageReady = true
        return@evaluateJavascript
      }
      if (data.optString("state") == "prepared") {
        pageReady = true
        if (data.optBoolean("passwordReady")) onPasswordFormReady()
        return@evaluateJavascript
      }
      if (!active) {
        if (data.optString("state") != "loading") pageReady = true
        return@evaluateJavascript
      }
      resumePending = false
      val serverStatus = data.optString("statusCode")
      if (serverStatus in setOf("0", "1", "2", "3") && serverStatus != lastServerStatus) {
        lastServerStatus = serverStatus
        onDiagnostic("INFO", "校方二维码状态=$serverStatus（" + when (serverStatus) {
          "1" -> "微信已确认"
          "2" -> "已扫码，等待确认"
          "3" -> "二维码已失效"
          else -> "等待扫码"
        } + "）")
      }
      pageState = data.optString("state")
      if (pageState !in setOf("loading", "wrong_page", "missing_script")) {
        pageReady = true
        pageActivated = true
      }
      if (data.optBoolean("refreshAccepted")) refreshPending = false
      resourceFailure?.let { fail(it); return@evaluateJavascript }
      updateSaveButton()
      when (pageState) {
        "expired" -> { clearImage(); fail("二维码已失效，请点击“重新获取”刷新当前二维码") }
        "scanned" -> showStatus("扫码成功，请在微信中确认登录")
        "confirmed" -> submitConfirmedLogin()
        "submitting" -> Unit
        "submit_error" -> fail("微信已确认，但二维码认证表单解析或提交失败，请关闭后重新获取二维码")
        "waiting" -> {
          if (data.has("dataUrl")) showImage(data)
          if (!terminalError) showStatus("请用微信扫码，并确认登录")
        }
        "loading", "pending_token", "pending_image", "missing_image" -> {
          val elapsed = SystemClock.elapsedRealtime() - waitingSince
          if (elapsed > 15_000L) {
            val step = when (pageState) {
              "pending_token", "missing_image" -> "学校二维码申请"
              "pending_image" -> "学校二维码图片加载"
              else -> "学校二维码脚本加载"
            }
            fail("$step 超时，请点击“重新获取”")
          }
        }
        "request_error" -> {
          val error = data.optJSONObject("error")
          val step = if (error?.optString("phase") == "status") "扫码状态检查" else "二维码申请"
          val detail = when (error?.optString("kind")) {
            "timeout" -> "连接超时"
            "http" -> "网站返回 HTTP ${error.optInt("code")}"
            "empty" -> "网站返回了空内容"
            "unexpected" -> "网站返回了无法识别的扫码状态"
            else -> "网络连接失败"
          }
          fail("学校$step 失败：$detail，请点击“重新获取”")
        }
        "missing_form", "missing_script", "missing_refresh", "wrong_page" -> {
          if (!pageReloadAttempted) reloadQrPage()
          else fail("二维码页面解析错误：电脑端认证页缺少扫码表单或刷新入口，请点击“重新获取”")
        }
        "wrong_image" -> fail("二维码地址解析错误：学校返回了非预期的图片地址")
        "image_error" -> fail(resourceFailure ?: "二维码图片加载失败，请点击“重新获取”")
        "read_error" -> fail("二维码图片读取失败：无法读取网页当前图片，请点击“重新获取”")
      }
      updateSaveButton()
    }
  }

  private fun reloadQrPage() {
    pageReloadAttempted = true
    refreshPending = false
    useDesktopUserAgent()
    webView.loadUrl(UniversityEndpoints.LOGIN_FORM)
  }

  private fun submitConfirmedLogin() {
    if (authenticationStarted) return
    authenticationStarted = true
    authenticationStartedAt = SystemClock.elapsedRealtime()
    authenticationProgressAt = authenticationStartedAt
    authenticationStep = "提交二维码认证"
    handler.removeCallbacks(poll)
    clearImage()
    showStatus("微信已确认，正在提交二维码认证…")
    restartAuthenticationWatchdog()
    val requestGeneration = generation
    webView.evaluateJavascript(IdentityQrPageScript.command("submit", "")) { raw ->
      if (!active || requestGeneration != generation) return@evaluateJavascript
      val state = runCatching { JSONObject(JSONArray("[$raw]").getString(0)).optString("state") }.getOrNull()
      if (state != "submitting") fail("微信已确认，但二维码认证表单提交失败，请关闭弹窗后重试")
    }
  }

  private fun restartAuthenticationWatchdog() {
    handler.removeCallbacks(authenticationWatchdog)
    if (hostResumed) handler.postDelayed(authenticationWatchdog, 1000L)
  }

  private fun isIdentityLoginPage(): Boolean {
    val uri = Uri.parse(webView.url.orEmpty())
    return uri.scheme == "https" && uri.host == "ids.njust.edu.cn" && uri.path == "/authserver/login"
  }

  private fun showImage(data: JSONObject) {
    val dataUrl = data.optString("dataUrl")
    val bitmap = runCatching {
      require(dataUrl.startsWith("data:image/png;base64,") && dataUrl.length < 2_000_000)
      val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
    if (bitmap == null) { fail("二维码图片解析错误，请点击“重新获取”"); return }
    imageUrl = data.optString("imageUrl")
    currentBitmap = bitmap
    imageView?.setImageBitmap(bitmap)
    updateSaveButton()
  }

  private fun clearImage() {
    imageUrl = null
    currentBitmap = null
    imageView?.setImageDrawable(null)
    updateSaveButton()
  }

  private fun fail(message: String) {
    terminalError = true
    refreshPending = false
    handler.removeCallbacks(poll)
    handler.removeCallbacks(authenticationWatchdog)
    if (isIdentityLoginPage()) webView.evaluateJavascript(IdentityQrPageScript.command("pause", ""), null)
    showStatus(message)
    updateSaveButton()
  }

  private fun updateSaveButton() {
    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = active &&
      !authenticationStarted &&
      (terminalError || (!refreshPending && pageState !in setOf("loading", "pending_token", "pending_image")))
    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
      isEnabled = active && !authenticationStarted && currentBitmap != null && !terminalError && !refreshPending && pageState != "expired" && !savingImage && pendingSaveGeneration == null
      text = if (savingImage) "保存中…" else "保存到相册"
    }
  }

  private fun saveCurrentImage() {
    if (!active || terminalError || refreshPending || savingImage || pendingSaveGeneration != null) return
    val bitmap = currentBitmap ?: return
    if (pageState == "expired") {
      showStatus("二维码已失效，请重新获取后保存")
      return
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
      ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      pendingSaveGeneration = generation
      updateSaveButton()
      requestGalleryPermission()
      return
    }
    savingImage = true
    val savedGeneration = generation
    updateSaveButton()
    // Save the displayed bitmap; fetching another image could change the login token.
    executor.execute {
      val message = try {
        QrImageGalleryStore.save(activity.applicationContext, bitmap)
        "二维码已保存到相册“周三课表”"
      } catch (error: Exception) {
        when (error) {
          is SecurityException -> "二维码保存失败：相册写入权限被拒绝，请检查系统权限"
          else -> "二维码保存失败：" + (error.message?.takeIf { it.startsWith("相册") || it.startsWith("二维码") }
            ?: "存储不可用或图片写入失败，请检查剩余空间后重试")
        }
      }
      handler.post {
        savingImage = false
        updateSaveButton()
        if (active && generation == savedGeneration && !terminalError) showStatus(message)
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun showStatus(message: String) {
    statusView?.text = message
    if (lastStatus != message) {
      lastStatus = message
      onStatus(message)
      onDiagnostic(if (terminalError) "FAIL" else "INFO", message)
    }
  }

  private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
