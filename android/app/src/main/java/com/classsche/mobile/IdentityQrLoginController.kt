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
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
  private val poll = object : Runnable {
    override fun run() {
      if (!active) return
      inspectPage()
      handler.postDelayed(this, 1000L)
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
    webView.settings.userAgentString = desktopUserAgent
    refresh()
    handler.postDelayed(poll, 1000L)
  }

  fun onLoginPageReady() {
    pageReady = true
    if (active) inspectPage()
  }

  fun onPageStarted() { pageReady = false }

  fun onPageError(message: String) {
    if (active) showStatus(message)
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

  fun restoreMobileUserAgent() {
    if (!active && webView.settings.userAgentString != mobileUserAgent) {
      webView.settings.userAgentString = mobileUserAgent
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
    dialog?.setOnDismissListener(null)
    dialog?.dismiss()
    dialog = null
    imageView = null
    statusView = null
    imageUrl = null
    currentBitmap = null
    pendingSaveGeneration = null
    onActiveChanged(false)
    if (reloadLoginPage) {
      webView.stopLoading()
      restoreMobileUserAgent()
      reloadPasswordLoginPage()
    }
  }

  private fun refresh() {
    generation++
    pageReady = false
    imageUrl = null
    currentBitmap = null
    pageState = "waiting"
    imageView?.setImageDrawable(null)
    updateSaveButton()
    showStatus("正在获取登录二维码…")
    webView.loadUrl(UniversityEndpoints.PORTAL_LOGIN)
  }

  private fun inspectPage() {
    if (!pageReady || Uri.parse(webView.url.orEmpty()).host != "ids.njust.edu.cn") return
    val requestGeneration = generation
    webView.evaluateJavascript("""
      (function() {
        const form = Array.from(document.querySelectorAll('#qrLoginForm')).find(el => el.querySelector('#qr_img')?.getAttribute('src'));
        if (!form) return JSON.stringify({ state: 'missing' });
        const visible = selector => {
          const el = form.querySelector(selector);
          return el && getComputedStyle(el).display !== 'none';
        };
        return JSON.stringify({
          imageUrl: form.querySelector('#qr_img').src,
          state: visible('#qr_invalid') ? 'expired' : visible('#qr_success') ? 'scanned' : 'waiting'
        });
      })();
    """.trimIndent()) { raw ->
      if (!active || requestGeneration != generation) return@evaluateJavascript
      val data = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull() ?: return@evaluateJavascript
      pageState = data.optString("state")
      updateSaveButton()
      when (pageState) {
        "missing" -> showStatus("电脑端认证页未返回二维码，请点击“重新获取”")
        "expired" -> showStatus("二维码已失效，请点击“重新获取”")
        "scanned" -> showStatus("扫码成功，请在微信中确认登录")
        "waiting" -> {
          val source = data.optString("imageUrl")
          if (source.isNotBlank() && source != imageUrl) fetchImage(source, requestGeneration)
        }
      }
    }
  }

  private fun fetchImage(source: String, requestGeneration: Int) {
    val uri = Uri.parse(source)
    if (uri.scheme != "https" || uri.host != "ids.njust.edu.cn" || uri.path != "/authserver/qrCode/getCode") {
      showStatus("二维码地址解析错误：返回了非预期的图片地址")
      return
    }
    imageUrl = source
    val cookies = CookieManager.getInstance().getCookie(source).orEmpty()
    executor.execute {
      try {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
          connectTimeout = 10_000
          readTimeout = 10_000
          instanceFollowRedirects = false
          useCaches = false
          setRequestProperty("User-Agent", desktopUserAgent)
          setRequestProperty("Referer", UniversityEndpoints.PORTAL_LOGIN)
          setRequestProperty("Accept", "image/*")
          if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
        }
        val bitmap = try {
          val code = connection.responseCode
          if (code in 300..399) throw IOException("二维码图片跳转错误（HTTP $code）")
          if (code != 200) throw IOException("二维码图片下载失败（HTTP $code）")
          connection.inputStream.use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("二维码图片解析错误")
        } finally { connection.disconnect() }
        handler.post {
          if (active && generation == requestGeneration && imageUrl == source) {
            currentBitmap = bitmap
            imageView?.setImageBitmap(bitmap)
            updateSaveButton()
            if (pageState == "waiting") showStatus("请用微信扫码，并确认登录")
          } else bitmap.recycle()
        }
      } catch (error: Exception) {
        handler.post {
          if (active && generation == requestGeneration) {
            // The source contains a login token; never include it or cookies in diagnostics.
            val detail = when (error) {
              is java.net.SocketTimeoutException -> "二维码图片连接超时，请点击“重新获取”"
              is java.net.UnknownHostException -> "二维码图片下载失败：无法解析学校域名"
              else -> error.message?.takeIf { it.startsWith("二维码") } ?: "二维码图片连接失败，请点击“重新获取”"
            }
            showStatus(detail)
          }
        }
      }
    }
  }

  private fun updateSaveButton() {
    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
      isEnabled = active && currentBitmap != null && pageState != "expired" && !savingImage && pendingSaveGeneration == null
      text = if (savingImage) "保存中…" else "保存到相册"
    }
  }

  private fun saveCurrentImage() {
    if (!active || savingImage || pendingSaveGeneration != null) return
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
        if (active && generation == savedGeneration) showStatus(message)
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun showStatus(message: String) {
    statusView?.text = message
    if (lastStatus != message) {
      lastStatus = message
      onStatus(message)
    }
  }

  private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
