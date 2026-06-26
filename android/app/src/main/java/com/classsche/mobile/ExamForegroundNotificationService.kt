package com.classsche.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

class ExamForegroundNotificationService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var stopRequestedByApp = false
  private var foregroundStarted = false
  private val refreshRunnable = object : Runnable {
    override fun run() {
      refreshNotificationState()
      handler.postDelayed(this, 60_000L)
    }
  }

  override fun onCreate() {
    super.onCreate()
    stopRequestedByApp = false
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!hasNotificationPermission() || !ExamOngoingNotificationScheduler.isEnabled(this)) {
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
    val exams = ExamNotificationHelper.loadExamOccurrences(this)
    val leadMinutes = ExamOngoingNotificationScheduler.getLeadMinutes(this)
    val activeExam = ExamNotificationHelper.findNotificationWindowExam(exams, leadMinutes)
    if (activeExam == null) {
      stopForegroundService()
      ExamOngoingNotificationScheduler.sync(this)
      return
    }

    val notification = buildOngoingNotification(activeExam)
    val manager = getSystemService(NotificationManager::class.java)
    if (!foregroundStarted) {
      startForeground(ONGOING_NOTIFICATION_ID, notification)
      foregroundStarted = true
    } else {
      manager.notify(ONGOING_NOTIFICATION_ID, notification)
    }
  }

  private fun buildOngoingNotification(exam: ExamOccurrence): Notification {
    val launchIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val now = LocalDateTime.now()
    val room = exam.exam.examRoom.ifBlank { "地点待确认" }
    val seat = exam.exam.seatNumber.ifBlank { "待确认" }
    val teacher = exam.exam.teacher.ifBlank { "未标注" }
    val title: String
    val text: String
    if (now.isBefore(exam.startAt)) {
      val hoursLeft = Duration.between(now, exam.startAt).toHours().coerceAtLeast(0)
      title = "考试：${exam.exam.courseName}"
      text = "$room $seat · ${formatExamStartTime(exam)} · $teacher · 还有${hoursLeft}小时"
    } else {
      title = "正在考试：${exam.exam.courseName}"
      text = "$room $seat · ${exam.exam.examTime} · $teacher"
    }

    return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(pendingIntent)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  private fun ensureChannel() {
    val channel = NotificationChannel(
      ONGOING_CHANNEL_ID,
      "考试提醒",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "显示考试前到考试结束期间的持续提醒"
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
    if (stopRequestedByApp || !ExamOngoingNotificationScheduler.isEnabled(this)) return
    val exams = ExamNotificationHelper.loadExamOccurrences(this)
    val leadMinutes = ExamOngoingNotificationScheduler.getLeadMinutes(this)
    val activeExam = ExamNotificationHelper.findNotificationWindowExam(exams, leadMinutes)
    if (activeExam != null) {
      ExamOngoingNotificationScheduler.scheduleRecovery(this)
    }
  }

  private fun formatExamStartTime(exam: ExamOccurrence): String =
    "${exam.date.monthValue}月${exam.date.dayOfMonth}日 ${exam.startTime.toString().take(5)}"

  companion object {
    private const val ONGOING_CHANNEL_ID = "classsche_exam_ongoing_v2"
    private const val ONGOING_NOTIFICATION_ID = 2102
  }
}
