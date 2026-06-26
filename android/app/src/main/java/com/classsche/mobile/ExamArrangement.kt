package com.classsche.mobile

data class ExamArrangement(
  val index: Int,
  val examSession: String,
  val courseCode: String,
  val courseName: String,
  val examTime: String,
  val examRoom: String,
  val seatNumber: String,
  val teacher: String = "",
  val rawText: String = ""
)
