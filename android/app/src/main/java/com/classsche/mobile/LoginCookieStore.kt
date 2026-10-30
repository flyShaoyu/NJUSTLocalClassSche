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
    // For example the IDS language cookie has Domain=edu.cn. Browsers ignore
    // that public-suffix cookie; it must not abort otherwise valid SSO cookies.
    val cookies = LoginCookiePolicy.filterHeaders(url, headers).filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
    if (cookies.isEmpty()) return
    check(Looper.myLooper() != Looper.getMainLooper()) { "后台登录必须在工作线程运行" }
    val manager = CookieManager.getInstance()
    val completed = CountDownLatch(cookies.size)
    val rejected = java.util.concurrent.ConcurrentLinkedQueue<String>()
    // A callback requires a Looper. Await completion before the next login reads the jar.
    Handler(Looper.getMainLooper()).post {
      cookies.forEach { cookie ->
        try {
          manager.setCookie(url, cookie) { success ->
            if (!success) rejected.add(cookie.substringBefore('=').trim().take(80))
            completed.countDown()
          }
        } catch (_: Exception) {
          rejected.add(cookie.substringBefore('=').trim().take(80))
          completed.countDown()
        }
      }
    }
    check(completed.await(5, TimeUnit.SECONDS)) { "登录会话 Cookie 保存超时，请稍后重试" }
    check(rejected.isEmpty()) { "登录会话 Cookie 保存失败：${java.net.URI(url).host}（${rejected.joinToString()}），系统 WebView 拒绝写入；请在登录页重新认证" }
  }

  override fun flush() = CookieManager.getInstance().flush()
}
