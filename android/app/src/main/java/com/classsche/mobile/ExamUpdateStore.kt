package com.classsche.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File

/** Shared by manual and automatic refresh so comparisons always use the same validated cache. */
internal object ExamUpdateStore {
  const val CACHE_FILE = "exam-list.json"
  const val ACTION_OPEN_EXAMS = "com.classsche.mobile.action.OPEN_EXAM_UPDATES"
  private const val CHANNEL = "classsche_exam_update_v1"
  private const val NOTIFICATION_ID = 3102

  @Synchronized fun save(context: Context, latest: List<ExamArrangement>): Int {
    val file = AtomicFile(File(context.filesDir, CACHE_FILE))
    val previous = runCatching { decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
    val changes = ExamUpdatePolicy.changes(previous, latest)
    val output = file.startWrite()
    try { output.write(ExamRenderer.toJson(latest).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
    catch (error: Exception) { file.failWrite(output); throw error }
    AppDebugLog.append(context, "EXAM_UPDATE", "SUCCESS", "考试缓存已更新，共 ${latest.size} 场，${changes.size} 项变动${if (previous == null) "（首次建立基线）" else ""}")
    if (changes.isNotEmpty()) runCatching { notifyChanges(context, changes) }
      .onFailure { AppDebugLog.append(context, "EXAM_UPDATE", "WARN", "考试缓存已保存，更新通知失败：${FailureDetails.describe(it)}") }
    // Reminder scheduling must not turn a successful data fetch into a failed sync.
    runCatching { ExamOngoingNotificationScheduler.sync(context) }
      .onFailure { AppDebugLog.append(context, "EXAM_UPDATE", "WARN", "考试缓存已保存，考前提醒重排失败：${FailureDetails.describe(it)}") }
    return changes.size
  }

  private fun decode(raw: String): List<ExamArrangement> {
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
      val item = array.getJSONObject(index)
      ExamArrangement(item.optInt("index", index + 1), item.getString("examSession"), item.getString("courseCode"),
        item.getString("courseName"), item.getString("examTime"), item.getString("examRoom"), item.getString("seatNumber"), item.optString("teacher"), item.optString("rawText"))
    }
  }

  private fun notifyChanges(context: Context, changes: List<ExamUpdatePolicy.Change>) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      AppDebugLog.append(context, "EXAM_UPDATE", "WARN", "系统未授予通知权限，跳过考试更新通知")
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "考试安排更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "考试安排新增、调整或从列表移除时提示"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE; setShowBadge(true)
    })
    val preview = changes.take(3).joinToString("\n") { change ->
      val type = when (change.kind) { ExamUpdatePolicy.Kind.ADDED -> "新增"; ExamUpdatePolicy.Kind.CHANGED -> "调整"; ExamUpdatePolicy.Kind.REMOVED -> "列表移除" }
      val exam = change.exam
      "$type：${exam.courseName} ${exam.examTime} ${exam.examRoom}${exam.seatNumber.takeIf { it.isNotBlank() }?.let { " 座位$it" }.orEmpty()}"
    }
    val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_EXAMS)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle("考试安排有 ${changes.size} 项更新")
      .setContentText(preview.lineSequence().firstOrNull().orEmpty())
      .setStyle(NotificationCompat.BigTextStyle().bigText("$preview\n点击查看最新考试安排"))
      .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
  }
}
