package com.classsche.mobile

internal object UniversityEndpoints {
  const val ORIGIN = "https://bkjw.njust.edu.cn"
  const val PORTAL_LOGIN = "https://ids.njust.edu.cn/authserver/login?service=https%3A%2F%2Fehall2.njust.edu.cn%2Flogin"
  // Same identity provider, with the teaching service as the CAS callback.
  const val LOGIN = "$ORIGIN/njlgdx/indexsso.jsp"
  const val TIMETABLE = "$ORIGIN/njlgdx/xskb/xskb_list.do"
  const val EXAM_QUERY = "$ORIGIN/njlgdx/xsks/xsksap_query"
  const val EXAM_LIST = "$ORIGIN/njlgdx/xsks/xsksap_list"
  const val SCORES = "$ORIGIN/njlgdx/kscj/cjcx_list"
  const val LEVEL_EXAMS = "$ORIGIN/njlgdx/kscj/djkscj_list"
}
