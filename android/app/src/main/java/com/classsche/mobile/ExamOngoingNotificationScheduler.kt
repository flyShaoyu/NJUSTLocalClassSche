package com.classsche.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId

object ExamOngoingNotificationScheduler {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_EXAM_NOTIFICATION_ENABLED = "exam_notification_enabled"
  private const val PREF_EXAM_NOTIFICATION_LEAD_MINUTES = "exam_notification_lead_minutes"
  private const val REQUEST_CODE_START = 4201
  private const val REQUEST_CODE_END = 4202
  private const val REQUEST_CODE_RECOVER = 4203
  const val ACTION_EXAM_REMINDER_START = "com.classsche.mobile.action.EXAM_REMINDER_START"
  const val ACTION_EXAM_REMINDER_END = "com.classsche.mobile.action.EXAM_REMINDER_END"
  const val ACTION_EXAM_REMINDER_RECOVER = "com.classsche.mobile.action.EXAM_REMINDER_RECOVER"

  fun sync(context: Context) {
    cancelScheduledAlarms(context)

    if (!isEnabled(context)) {
      context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
      return
    }

    val exams = ExamNotificationHelper.loadExamOccurrences(context)
    if (exams.isEmpty()) {
      context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
      return
    }

    val leadMinutes = getLeadMinutes(context)
    val now = LocalDateTime.now()
    val activeExam = ExamNotificationHelper.findNotificationWindowExam(exams, leadMinutes, now)
    if (activeExam != null) {
      scheduleEnd(context, activeExam)
      ContextCompat.startForegroundService(context, Intent(context, ExamForegroundNotificationService::class.java))
      return
    }

    val nextExam = ExamNotificationHelper.findNextExam(exams, now)
    if (nextExam == null) {
      context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
      return
    }

    val startAt = nextExam.startAt.minusMinutes(leadMinutes.toLong())
    if (startAt.isAfter(now)) {
      context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
      scheduleReminderStart(context, startAt)
    } else {
      scheduleEnd(context, nextExam)
      ContextCompat.startForegroundService(context, Intent(context, ExamForegroundNotificationService::class.java))
    }
  }

  fun handleAlarm(context: Context, action: String?) {
    when (action) {
      ACTION_EXAM_REMINDER_START -> sync(context)
      ACTION_EXAM_REMINDER_END -> {
        context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
        sync(context)
      }
      ACTION_EXAM_REMINDER_RECOVER -> sync(context)
      else -> sync(context)
    }
  }

  fun scheduleRecovery(context: Context, delayMillis: Long = 1200L) {
    val triggerAtMillis = System.currentTimeMillis() + delayMillis.coerceAtLeast(250L)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      REQUEST_CODE_RECOVER,
      Intent(context, ExamNotificationReceiver::class.java).setAction(ACTION_EXAM_REMINDER_RECOVER),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
  }

  fun cancelAll(context: Context) {
    cancelScheduledAlarms(context)
    context.stopService(Intent(context, ExamForegroundNotificationService::class.java))
  }

  fun isEnabled(context: Context): Boolean =
    prefs(context).getBoolean(PREF_EXAM_NOTIFICATION_ENABLED, false)

  fun saveEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(PREF_EXAM_NOTIFICATION_ENABLED, enabled).apply()
  }

  fun getLeadMinutes(context: Context): Int =
    prefs(context).getInt(PREF_EXAM_NOTIFICATION_LEAD_MINUTES, defaultLeadMinutes())
      .takeIf { it in leadOptions() } ?: defaultLeadMinutes()

  fun saveLeadMinutes(context: Context, minutes: Int) {
    val safeMinutes = minutes.takeIf { it in leadOptions() } ?: defaultLeadMinutes()
    prefs(context).edit().putInt(PREF_EXAM_NOTIFICATION_LEAD_MINUTES, safeMinutes).apply()
  }

  fun defaultLeadMinutes(): Int = 24 * 60

  fun leadOptions(): List<Int> = listOf(
    60,
    2 * 60,
    3 * 60,
    5 * 60,
    12 * 60,
    24 * 60,
    48 * 60,
    72 * 60
  )

  fun formatLeadLabel(minutes: Int): String = when (minutes) {
    60 -> "1小时"
    120 -> "2小时"
    180 -> "3小时"
    300 -> "5小时"
    720 -> "12小时"
    1440 -> "24小时"
    2880 -> "48小时"
    4320 -> "72小时"
    else -> "${minutes / 60}小时"
  }

  fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    return alarmManager.canScheduleExactAlarms()
  }

  private fun scheduleReminderStart(context: Context, at: LocalDateTime) {
    scheduleAlarm(context, REQUEST_CODE_START, ACTION_EXAM_REMINDER_START, at)
  }

  private fun scheduleEnd(context: Context, occurrence: ExamOccurrence) {
    scheduleAlarm(context, REQUEST_CODE_END, ACTION_EXAM_REMINDER_END, occurrence.endAt)
  }

  private fun scheduleAlarm(
    context: Context,
    requestCode: Int,
    action: String,
    at: LocalDateTime
  ) {
    val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      requestCode,
      Intent(context, ExamNotificationReceiver::class.java).setAction(action),
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
      REQUEST_CODE_START to ACTION_EXAM_REMINDER_START,
      REQUEST_CODE_END to ACTION_EXAM_REMINDER_END,
      REQUEST_CODE_RECOVER to ACTION_EXAM_REMINDER_RECOVER
    ).forEach { (requestCode, action) ->
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ExamNotificationReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      alarmManager.cancel(pendingIntent)
      pendingIntent.cancel()
    }
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
