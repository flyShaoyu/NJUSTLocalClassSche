package com.classsche.mobile

import java.net.URI

/** Known CAS landing pages shared by password and QR login; never triggers authentication itself. */
internal class LoginNavigationPolicy {
  data class Step(val targetUrl: String, val shouldLoad: Boolean)

  private val requestedTargets = mutableSetOf<String>()

  fun reset() = requestedTargets.clear()

  fun nextStep(url: String, loginRequested: Boolean): Step? {
    if (!loginRequested) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
    val target = when {
      uri.host?.lowercase() == "ehall2.njust.edu.cn" &&
        uri.path in setOf("/index.html", "/default/index.html", "/default/index") -> UniversityEndpoints.LOGIN
      uri.host?.lowercase() == "bkjw.njust.edu.cn" &&
        uri.path == "/njlgdx/framework/main.jsp" -> UniversityEndpoints.TIMETABLE
      else -> return null
    }
    // Commit-visible and page-finished can both report the same intermediate page.
    return Step(target, requestedTargets.add(target))
  }
}
