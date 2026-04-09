package com.classsche.mobile

data class TimetableCourse(
  val courseName: String,
  val weekday: String,
  val periods: String,
  val classroom: String,
  val weeks: String,
  val teacher: String = "",
  val courseCode: String = "",
  val courseSequence: String = "",
  val rawText: String = ""
)
