package com.classsche.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object HeadlessScoreSyncScheduler {
  private const val REQUEST_CODE_SYNC = 3301
  private const val INTERVAL_MILLIS = 2L * 60L * 60L * 1000L
  const val ACTION_SYNC = "com.classsche.mobile.action.HEADLESS_SCORE_SYNC"

  fun scheduleNext(context: Context, requestedDelayMillis: Long? = null) {
    val appContext = context.applicationContext
    val settings = ScoreSyncSettings.read(appContext)
    if (settings.mode == ScoreSyncSettings.Mode.DISABLED) {
      cancel(appContext)
      AppDebugLog.append(appContext, "HEADLESS_SCORE_SCHEDULE", "INFO", "后台成绩拉取已关闭，取消定时任务")
      return
    }
    val delayMillis = (requestedDelayMillis ?: settings.intervalMillis).coerceAtLeast(60_000L)
    val triggerAtMillis = System.currentTimeMillis() + delayMillis
    val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    val pendingIntent = buildPendingIntent(appContext)
    if (canScheduleExactAlarms(appContext)) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
    AppDebugLog.append(
      appContext,
      "HEADLESS_SCORE_SCHEDULE",
      "INFO",
      "已安排下次成绩后台同步，${delayMillis.coerceAtLeast(60_000L) / 1000}s 后触发"
    )
  }

  fun handleAlarm(
    context: Context,
    action: String?,
    pendingResult: BroadcastReceiver.PendingResult
  ) {
    scheduleNext(context)
    AppDebugLog.append(
      context.applicationContext,
      "HEADLESS_SCORE_SCHEDULE",
      "INFO",
      "收到成绩后台同步闹钟 action=${action ?: "-"}"
    )
    val skipReason = ScoreSyncSettings.skipReason(context.applicationContext)
    if (skipReason != null) {
      AppDebugLog.append(context.applicationContext, "HEADLESS_SCORE_SYNC", "SKIP", skipReason)
      pendingResult.finish()
      return
    }
    HeadlessScoreSyncManager.runSync(context.applicationContext, reason = "ALARM") {
      pendingResult.finish()
    }
  }

  fun onSystemEvent(context: Context, event: String?) {
    AppDebugLog.append(
      context.applicationContext,
      "HEADLESS_SCORE_SCHEDULE",
      "INFO",
      "收到系统事件 ${event ?: "-"}，重新安排成绩后台同步"
    )
    scheduleNext(context)
  }

  fun cancel(context: Context) {
    val alarmManager = context.applicationContext.getSystemService(AlarmManager::class.java)
    val pendingIntent = buildPendingIntent(context.applicationContext)
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
    AppDebugLog.append(context.applicationContext, "HEADLESS_SCORE_SCHEDULE", "INFO", "已取消成绩后台同步闹钟")
  }

  fun onSettingsChanged(context: Context) {
    val appContext = context.applicationContext
    val settings = ScoreSyncSettings.read(appContext)
    if (settings.mode == ScoreSyncSettings.Mode.DISABLED) {
      cancel(appContext)
      AppDebugLog.append(appContext, "HEADLESS_SCORE_SCHEDULE", "INFO", "后台成绩拉取设置已关闭")
      return
    }
    AppDebugLog.append(
      appContext,
      "HEADLESS_SCORE_SCHEDULE",
      "INFO",
      "后台成绩拉取设置已更新，模式=${settings.mode.storageValue}，间隔=${settings.intervalHours}h"
    )
    scheduleNext(appContext)
  }

  private fun buildPendingIntent(context: Context): PendingIntent {
    return PendingIntent.getBroadcast(
      context,
      REQUEST_CODE_SYNC,
      Intent(context, HeadlessScoreSyncReceiver::class.java).setAction(ACTION_SYNC),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    return alarmManager.canScheduleExactAlarms()
  }
}
