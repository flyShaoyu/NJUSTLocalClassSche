package com.classsche.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.ZoneId

object CourseNotificationScheduler {
  private const val REQUEST_CODE_START = 3001
  private const val REQUEST_CODE_END = 3002
  private const val REQUEST_CODE_RECOVER = 3003
  const val ACTION_REMINDER_START = "com.classsche.mobile.action.REMINDER_START"
  const val ACTION_REMINDER_END = "com.classsche.mobile.action.REMINDER_END"
  const val ACTION_REMINDER_RECOVER = "com.classsche.mobile.action.REMINDER_RECOVER"

  fun sync(context: Context) {
    cancelScheduledAlarms(context)

    if (!CourseNotificationService.isEnabled(context)) {
      context.stopService(Intent(context, CourseNotificationService::class.java))
      return
    }

    val courses = TimetableScheduleHelper.loadCoursesFromCacheJson(context)
    if (courses.isEmpty()) {
      context.stopService(Intent(context, CourseNotificationService::class.java))
      return
    }

    val leadMinutes = CourseNotificationService.getLeadMinutes(context)
    val now = java.time.LocalDateTime.now()
    val activeCourse = TimetableScheduleHelper.findNotificationWindowCourse(courses, leadMinutes, now)
    if (activeCourse != null) {
      scheduleEnd(context, activeCourse)
      ContextCompat.startForegroundService(context, Intent(context, CourseNotificationService::class.java))
      return
    }

    val nextCourse = TimetableScheduleHelper.findNextCourse(courses, now)
    if (nextCourse == null) {
      context.stopService(Intent(context, CourseNotificationService::class.java))
      return
    }
    val startAt = nextCourse.startAt.minusMinutes(leadMinutes.toLong())
    if (startAt.isAfter(now)) {
      context.stopService(Intent(context, CourseNotificationService::class.java))
      scheduleReminderStart(context, startAt)
    } else {
      scheduleEnd(context, nextCourse)
      ContextCompat.startForegroundService(context, Intent(context, CourseNotificationService::class.java))
    }
  }

  fun handleAlarm(context: Context, action: String?) {
    when (action) {
      ACTION_REMINDER_START -> sync(context)
      ACTION_REMINDER_END -> {
        context.stopService(Intent(context, CourseNotificationService::class.java))
        sync(context)
      }
      ACTION_REMINDER_RECOVER -> sync(context)
      else -> sync(context)
    }
  }

  fun scheduleRecovery(context: Context, delayMillis: Long = 1200L) {
    val triggerAtMillis = System.currentTimeMillis() + delayMillis.coerceAtLeast(250L)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      REQUEST_CODE_RECOVER,
      Intent(context, CourseNotificationAlarmReceiver::class.java).setAction(ACTION_REMINDER_RECOVER),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
  }

  fun cancelAll(context: Context) {
    cancelScheduledAlarms(context)
    context.stopService(Intent(context, CourseNotificationService::class.java))
  }

  fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    return alarmManager.canScheduleExactAlarms()
  }

  private fun scheduleReminderStart(context: Context, at: java.time.LocalDateTime) {
    scheduleAlarm(context, REQUEST_CODE_START, ACTION_REMINDER_START, at)
  }

  private fun scheduleEnd(context: Context, occurrence: CourseOccurrence) {
    scheduleAlarm(context, REQUEST_CODE_END, ACTION_REMINDER_END, occurrence.endAt)
  }

  private fun scheduleAlarm(
    context: Context,
    requestCode: Int,
    action: String,
    at: java.time.LocalDateTime
  ) {
    val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      requestCode,
      Intent(context, CourseNotificationAlarmReceiver::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    if (canScheduleExactAlarms(context)) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
  }

  private fun cancelScheduledAlarms(context: Context) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    listOf(
      REQUEST_CODE_START to ACTION_REMINDER_START,
      REQUEST_CODE_END to ACTION_REMINDER_END,
      REQUEST_CODE_RECOVER to ACTION_REMINDER_RECOVER
    ).forEach { (requestCode, action) ->
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, CourseNotificationAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      alarmManager.cancel(pendingIntent)
      pendingIntent.cancel()
    }
  }
}
