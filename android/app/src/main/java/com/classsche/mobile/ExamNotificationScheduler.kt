package com.classsche.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId

object ExamNotificationScheduler {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_EXAM_NOTIFICATION_ENABLED = "exam_notification_enabled"
  private const val PREF_EXAM_NOTIFICATION_LEAD_MINUTES = "exam_notification_lead_minutes"
  private const val PREF_EXAM_NOTIFICATION_LAST_KEY = "exam_notification_last_key"
  private const val REQUEST_CODE_REMINDER = 4101
  private const val REMINDER_CHANNEL_ID = "classsche_exam_reminder_v1"
  private const val REMINDER_NOTIFICATION_ID = 2101
  const val ACTION_EXAM_REMINDER = "com.classsche.mobile.action.EXAM_REMINDER"
  private const val REQUEST_CODE_START = 4101
  private const val REQUEST_CODE_END = 4102
  private const val REQUEST_CODE_RECOVER = 4103
  const val ACTION_EXAM_REMINDER_START = "com.classsche.mobile.action.EXAM_REMINDER_START"
  const val ACTION_EXAM_REMINDER_END = "com.classsche.mobile.action.EXAM_REMINDER_END"
  const val ACTION_EXAM_REMINDER_RECOVER = "com.classsche.mobile.action.EXAM_REMINDER_RECOVER"

  fun sync(context: Context) {
    cancelScheduledAlarms(context)

    if (!isEnabled(context)) {
      context.stopService(Intent(context, ExamNotificationService::class.java))
      return
    }

    val exams = ExamNotificationHelper.loadExamOccurrences(context)
    if (exams.isEmpty()) {
      context.stopService(Intent(context, ExamNotificationService::class.java))
      return
    }

    val leadMinutes = getLeadMinutes(context)
    val now = LocalDateTime.now()
    val activeExam = ExamNotificationHelper.findNotificationWindowExam(exams, leadMinutes, now)
    if (activeExam != null) {
      scheduleEnd(context, activeExam)
      ContextCompat.startForegroundService(context, Intent(context, ExamNotificationService::class.java))
      return
    }

    val nextExam = ExamNotificationHelper.findNextExam(exams, now)
    if (nextExam == null) {
      context.stopService(Intent(context, ExamNotificationService::class.java))
      return
    }

    val startAt = nextExam.startAt.minusMinutes(leadMinutes.toLong())
    if (startAt.isAfter(now)) {
      context.stopService(Intent(context, ExamNotificationService::class.java))
      scheduleReminderStart(context, startAt)
    } else {
      scheduleEnd(context, nextExam)
      ContextCompat.startForegroundService(context, Intent(context, ExamNotificationService::class.java))
    }
  }

  fun handleAlarm(context: Context, action: String?) {
    when (action) {
      ACTION_EXAM_REMINDER_START -> sync(context)
      ACTION_EXAM_REMINDER_END -> {
        context.stopService(Intent(context, ExamNotificationService::class.java))
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
    context.stopService(Intent(context, ExamNotificationService::class.java))
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

  private fun scheduleReminderStart(context: Context, at: LocalDateTime) {
    scheduleAlarm(context, at)
  }

  private fun scheduleEnd(context: Context, occurrence: ExamOccurrence) {
    scheduleAlarm(context, occurrence.endAt)
  }

  private fun scheduleAlarm(context: Context, at: LocalDateTime) {
    val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      REQUEST_CODE_REMINDER,
      Intent(context, ExamNotificationReceiver::class.java).setAction(ACTION_EXAM_REMINDER),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    if (CourseNotificationScheduler.canScheduleExactAlarms(context)) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
  }

  private fun cancelScheduledAlarms(context: Context) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      REQUEST_CODE_REMINDER,
      Intent(context, ExamNotificationReceiver::class.java).setAction(ACTION_EXAM_REMINDER),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
  }

  private fun showReminder(context: Context, occurrence: ExamOccurrence) {
    ensureChannel(context)
    val launchIntent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val room = occurrence.exam.examRoom.ifBlank { "待确认" }
    val seat = occurrence.exam.seatNumber.ifBlank { "待确认" }
    val teacher = occurrence.exam.teacher.ifBlank { "未标注" }
    val bigText = buildString {
      append("考试时间：").append(occurrence.exam.examTime.ifBlank { "待确认" })
      append("\n考试地点：").append(room)
      append("\n座位号：").append(seat)
      append("\n任课老师：").append(teacher)
    }
    context.getSystemService(NotificationManager::class.java).notify(
      REMINDER_NOTIFICATION_ID,
      NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("考试提醒：${occurrence.exam.courseName.ifBlank { "未命名考试" }}")
        .setContentText("${occurrence.exam.examTime} · ${room} · 座位号 ${seat}")
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    )
  }

  private fun ensureChannel(context: Context) {
    val channel = NotificationChannel(
      REMINDER_CHANNEL_ID,
      "考试提醒",
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      description = "用于显示考试前的时间、地点、座位号和任课老师提醒"
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      setShowBadge(true)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun cancelNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(REMINDER_NOTIFICATION_ID)
  }

  private fun getLastNotifiedKey(context: Context): String =
    prefs(context).getString(PREF_EXAM_NOTIFICATION_LAST_KEY, "").orEmpty()

  private fun saveLastNotifiedKey(context: Context, key: String) {
    prefs(context).edit().putString(PREF_EXAM_NOTIFICATION_LAST_KEY, key).apply()
  }

  private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
