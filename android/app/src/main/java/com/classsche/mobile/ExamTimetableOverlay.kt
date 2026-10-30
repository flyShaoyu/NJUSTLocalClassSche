package com.classsche.mobile

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Display-only exam entries: never write these back into official courses or reminders. */
object ExamTimetableOverlay {
  fun courses(exams: List<ExamOccurrence>, week1Monday: LocalDate?): List<TimetableCourse> {
    if (week1Monday == null) return emptyList()
    val slots = TimetableScheduleHelper.periodSlots.filterKeys { it <= 13 }
    return exams.distinctBy { it.notificationKey }.mapNotNull { occurrence ->
      val week = 1 + Math.floorDiv(ChronoUnit.DAYS.between(week1Monday, occurrence.date).toInt(), 7)
      if (week !in 1..30 || occurrence.endTime <= occurrence.startTime) return@mapNotNull null
      val touched = slots.filterValues { (a,b) -> LocalTime.parse(b) > occurrence.startTime && LocalTime.parse(a) < occurrence.endTime }.keys.sorted()
      val first = touched.firstOrNull() ?: slots.entries.minByOrNull { (_, t) -> kotlin.math.abs(java.time.Duration.between(occurrence.startTime, LocalTime.parse(t.first)).toMinutes()) }!!.key
      val last = touched.lastOrNull() ?: first
      val exam = occurrence.exam
      TimetableCourse(
        courseName = "【考试】${exam.courseName}", weekday = "星期" + listOf("一","二","三","四","五","六","日")[occurrence.date.dayOfWeek.value-1],
        periods = "$first-${last}节", classroom = exam.examRoom, weeks = "${week}周", teacher = exam.teacher,
        courseCode = "exam:${occurrence.notificationKey}", courseType = "考试",
        startTime = occurrence.startTime.toString(), endTime = occurrence.endTime.toString(),
        seatNumber = exam.seatNumber
      )
    }
  }
}
