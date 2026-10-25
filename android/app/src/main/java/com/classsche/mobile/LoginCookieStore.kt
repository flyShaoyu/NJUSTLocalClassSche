package com.classsche.mobile

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shares server-issued cookies with the existing WebView storage, including deletions. */
internal interface LoginCookieStore {
  fun read(url: String): String
  fun receive(url: String, headers: Map<String, List<String>>)
  fun flush()
}

internal object WebViewLoginCookieStore : LoginCookieStore {
  override fun read(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

  override fun receive(url: String, headers: Map<String, List<String>>) {
    val cookies = headers.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
    if (cookies.isEmpty()) return
    check(Looper.myLooper() != Looper.getMainLooper()) { "后台登录必须在工作线程运行" }
    val manager = CookieManager.getInstance()
    val completed = CountDownLatch(cookies.size)
    val accepted = java.util.concurrent.atomic.AtomicBoolean(true)
    // A callback requires a Looper. Await completion before the next login reads the jar.
    Handler(Looper.getMainLooper()).post {
      cookies.forEach { cookie ->
        try {
          manager.setCookie(url, cookie) { success ->
            if (!success) accepted.set(false)
            completed.countDown()
          }
        } catch (_: Exception) {
          accepted.set(false)
          completed.countDown()
        }
      }
    }
    check(completed.await(5, TimeUnit.SECONDS)) { "登录会话 Cookie 保存超时，请稍后重试" }
    check(accepted.get()) { "登录会话 Cookie 保存失败，请检查系统 WebView 状态" }
  }

  override fun flush() = CookieManager.getInstance().flush()
}
