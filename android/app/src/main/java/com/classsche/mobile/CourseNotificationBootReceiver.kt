package com.classsche.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CourseNotificationBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    CourseNotificationScheduler.sync(context)
    ExamOngoingNotificationScheduler.sync(context)
    HeadlessScoreSyncScheduler.onSystemEvent(context, intent?.action)
  }
}
