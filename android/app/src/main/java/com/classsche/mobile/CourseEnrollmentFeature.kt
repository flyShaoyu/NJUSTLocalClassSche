package com.classsche.mobile

import org.json.JSONObject

/** Local catalogue browsing is available. Actual enrollment and polling remain disabled. */
internal object CourseEnrollmentFeature {
  private const val RELEASE_ENABLED = false
  private const val SCHOOL_ADAPTER_READY = false
  const val PAGE = "enrollment"
  const val TITLE = "选课 / 抢课"
  const val UNAVAILABLE_MESSAGE = "选课 / 抢课功能暂未开放"
  const val browseEnabled = true
  val enabled: Boolean get() = RELEASE_ENABLED && SCHOOL_ADAPTER_READY

  fun status(): JSONObject = JSONObject()
    .put("enabled", enabled)
    .put("browseEnabled", browseEnabled)
    .put("submissionEnabled", enabled)
    .put("state", "catalog_only")
    .put("title", TITLE)
    .put("message", UNAVAILABLE_MESSAGE)
    .put("entryUrl", UniversityEndpoints.COURSE_ENROLLMENT)

  fun unavailable(): JSONObject = JSONObject().put("ok", false)
    .put("code", "FEATURE_DISABLED").put("error", UNAVAILABLE_MESSAGE)

  // Before enabling: capture an open batch, retain its real form/CSRF fields,
  // distinguish query/save/enroll/cancel endpoints, and validate authoritative
  // enrollment results. Do not infer an API or start retries from an empty page.
}
