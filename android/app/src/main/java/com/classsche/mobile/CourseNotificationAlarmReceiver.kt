package com.classsche.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CourseNotificationAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    CourseNotificationScheduler.handleAlarm(context, intent?.action)
  }
}
