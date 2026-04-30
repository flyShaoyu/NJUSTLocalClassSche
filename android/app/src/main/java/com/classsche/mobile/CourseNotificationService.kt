package com.classsche.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.LocalDateTime

class CourseNotificationService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var stopRequestedByApp = false
  private val refreshRunnable = object : Runnable {
    override fun run() {
      refreshNotificationState()
      handler.postDelayed(this, 60_000L)
    }
  }
  private var foregroundStarted = false

  override fun onCreate() {
    super.onCreate()
    stopRequestedByApp = false
    ensureChannels()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!hasNotificationPermission() || !isEnabled(this)) {
      stopForegroundService()
      return START_NOT_STICKY
    }

    refreshNotificationState()
    handler.removeCallbacks(refreshRunnable)
    handler.postDelayed(refreshRunnable, 60_000L)
    return START_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(refreshRunnable)
    scheduleRecoveryIfNeeded()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onTaskRemoved(rootIntent: Intent?) {
    scheduleRecoveryIfNeeded()
    super.onTaskRemoved(rootIntent)
  }

  private fun refreshNotificationState() {
    val courses = TimetableScheduleHelper.loadCoursesFromCacheJson(this)
    val leadMinutes = getLeadMinutes(this)
    val activeCourse = TimetableScheduleHelper.findNotificationWindowCourse(courses, leadMinutes)
    if (activeCourse == null) {
      stopForegroundService()
      CourseNotificationScheduler.sync(this)
      return
    }
    val notification = buildOngoingNotification(activeCourse)
    val manager = getSystemService(NotificationManager::class.java)
    if (!foregroundStarted) {
      startForeground(ONGOING_NOTIFICATION_ID, notification)
      foregroundStarted = true
    } else {
      manager.notify(ONGOING_NOTIFICATION_ID, notification)
    }
  }

  private fun buildOngoingNotification(course: CourseOccurrence): Notification {
    val launchIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val now = LocalDateTime.now()
    val minutesLeft = Duration.between(now, course.startAt).toMinutes().coerceAtLeast(0)
    val title: String
    val text: String
    if (now.isBefore(course.startAt)) {
      title = "下节课：${course.course.courseName}"
      text = "${course.course.classroom.ifBlank { "地点待定" }} · ${TimetableScheduleHelper.formatCourseStartTime(course)} · 还有${minutesLeft}分钟上课"
    } else {
      title = "正在上课：${course.course.courseName}"
      text = "${course.course.classroom.ifBlank { "地点待定" }} · ${TimetableScheduleHelper.formatCourseTimeRange(course)}"
    }

    return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(pendingIntent)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  private fun ensureChannels() {
    val manager = getSystemService(NotificationManager::class.java)
    val ongoingChannel = NotificationChannel(
      ONGOING_CHANNEL_ID,
      "课表常驻通知",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "显示课表提醒状态和即将开始的课程"
      setShowBadge(false)
    }
    manager.createNotificationChannel(ongoingChannel)
  }

  private fun hasNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  private fun stopForegroundService() {
    stopRequestedByApp = true
    foregroundStarted = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun scheduleRecoveryIfNeeded() {
    if (stopRequestedByApp || !isEnabled(this)) {
      return
    }
    val courses = TimetableScheduleHelper.loadCoursesFromCacheJson(this)
    val leadMinutes = getLeadMinutes(this)
    val activeCourse = TimetableScheduleHelper.findNotificationWindowCourse(courses, leadMinutes)
    if (activeCourse != null) {
      CourseNotificationScheduler.scheduleRecovery(this)
    }
  }

  companion object {
    private const val PREFS_NAME = "classsche_prefs"
    const val PREF_NOTIFICATION_ENABLED = "notification_enabled"
    const val PREF_NOTIFICATION_LEAD_HOURS = "notification_lead_hours"
    const val PREF_NOTIFICATION_LEAD_MINUTES = "notification_lead_minutes"
    private const val ONGOING_CHANNEL_ID = "classsche_course_ongoing"
    private const val ONGOING_NOTIFICATION_ID = 2001

    fun isEnabled(context: Context): Boolean =
      prefs(context).getBoolean(PREF_NOTIFICATION_ENABLED, false)

    fun getLeadHours(context: Context): Int =
      prefs(context).getInt(PREF_NOTIFICATION_LEAD_HOURS, 0).coerceIn(0, 23)

    fun getLeadMinutes(context: Context): Int {
      val hours = getLeadHours(context)
      val minutes = prefs(context).getInt(PREF_NOTIFICATION_LEAD_MINUTES, 15).coerceIn(0, 59)
      return hours * 60 + minutes
    }

    fun getLeadMinutePart(context: Context): Int =
      prefs(context).getInt(PREF_NOTIFICATION_LEAD_MINUTES, 15).coerceIn(0, 59)

    fun saveEnabled(context: Context, enabled: Boolean) {
      prefs(context).edit().putBoolean(PREF_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun saveLeadTime(context: Context, hours: Int, minutes: Int) {
      prefs(context).edit()
        .putInt(PREF_NOTIFICATION_LEAD_HOURS, hours.coerceIn(0, 23))
        .putInt(PREF_NOTIFICATION_LEAD_MINUTES, minutes.coerceIn(0, 59))
        .apply()
    }

    fun sync(context: Context) {
      val serviceIntent = Intent(context, CourseNotificationService::class.java)
      if (isEnabled(context)) {
        ContextCompat.startForegroundService(context, serviceIntent)
      } else {
        context.stopService(serviceIntent)
      }
    }

    private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }
}
