package com.classsche.mobile

/** Compare school fields, ignoring row order, cached teachers and display-only raw text. */
internal object ExamUpdatePolicy {
  enum class Kind { ADDED, CHANGED, REMOVED }
  data class Change(val kind: Kind, val exam: ExamArrangement)

  fun changes(previous: List<ExamArrangement>?, latest: List<ExamArrangement>): List<Change> {
    if (previous == null) return emptyList() // First successful pull establishes a baseline.
    val remaining = previous.toMutableList()
    val unmatched = latest.filter { exam ->
      val match = remaining.indexOfFirst { fingerprint(it) == fingerprint(exam) }
      if (match >= 0) { remaining.removeAt(match); false } else true
    }
    val updates = unmatched.map { exam ->
      val match = remaining.indexOfFirst { identity(it) == identity(exam) }
      if (match >= 0) { remaining.removeAt(match); Change(Kind.CHANGED, exam) }
      else Change(Kind.ADDED, exam)
    }
    return updates + remaining.map { Change(Kind.REMOVED, it) }
  }

  private fun clean(value: String) = value.replace(Regex("\\s+|\\u00a0"), "")
  private fun identity(exam: ExamArrangement) = listOf(exam.examSession, exam.courseCode.ifBlank { exam.courseName }).map(::clean)
  private fun fingerprint(exam: ExamArrangement) = listOf(exam.examSession, exam.courseCode, exam.courseName, exam.examTime, exam.examRoom, exam.seatNumber).map(::clean)
}
