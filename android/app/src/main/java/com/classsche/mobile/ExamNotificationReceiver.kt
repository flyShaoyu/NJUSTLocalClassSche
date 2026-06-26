package com.classsche.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExamNotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    ExamOngoingNotificationScheduler.handleAlarm(context, intent?.action)
  }
}
