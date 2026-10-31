package com.classsche.mobile

import android.content.Context

/** Install-time defaults only: upgrading must not override an existing user's choices. */
internal object NotificationDefaults {
  @Synchronized fun initialize(context: Context) {
    val prefs=context.getSharedPreferences("classsche_prefs",Context.MODE_PRIVATE)
    if(prefs.getBoolean("notification_defaults_v2",false))return
    val fresh=prefs.all.isEmpty() && !java.io.File(context.filesDir,"timetable.json").exists() &&
      !java.io.File(context.noBackupFilesDir,"school_mail_credentials.enc").exists() &&
      !java.io.File(context.noBackupFilesDir,"library_credentials.enc").exists() &&
      context.getSharedPreferences("school_mail_poll",Context.MODE_PRIVATE).all.isEmpty()
    val edit=prefs.edit().putBoolean("notification_defaults_v2",true)
    if(!prefs.contains("notification_enabled"))edit.putBoolean("notification_enabled",fresh)
    if(!prefs.contains("exam_notification_enabled"))edit.putBoolean("exam_notification_enabled",fresh)
    if(fresh)edit.putBoolean("initial_notification_permission_pending",true)
    check(edit.commit())
    val mail=context.getSharedPreferences("school_mail_poll",Context.MODE_PRIVATE)
    val mailEdit=mail.edit()
    if(!mail.contains("minutes"))mailEdit.putInt("minutes",if(fresh)15 else 0)
    if(!mail.contains("notifications"))mailEdit.putBoolean("notifications",fresh||mail.getInt("minutes",0)>0)
    check(mailEdit.commit())
  }
  fun consumePermissionPrompt(context:Context):Boolean {
    val prefs=context.getSharedPreferences("classsche_prefs",Context.MODE_PRIVATE)
    if(!prefs.getBoolean("initial_notification_permission_pending",false))return false
    return prefs.edit().putBoolean("initial_notification_permission_pending",false).commit()
  }
}
