package com.classsche.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HeadlessScoreSyncReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val pendingResult = goAsync()
    HeadlessScoreSyncScheduler.handleAlarm(context.applicationContext, intent?.action, pendingResult)
  }
}
