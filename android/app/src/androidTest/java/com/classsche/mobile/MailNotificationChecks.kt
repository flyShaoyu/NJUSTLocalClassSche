package com.classsche.mobile

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Separate files/preferences, synthetic messages only; never changes real mailbox credentials. */
internal object MailNotificationChecks {
  fun run(base:Context,report:(String)->Unit){
    val prefix="notification_test_"+System.nanoTime()
    val dir=File(base.cacheDir,prefix).apply{mkdirs()}
    val names=mutableSetOf<String>()
    val ctx=object:ContextWrapper(base){
      override fun getApplicationContext():Context=this
      override fun getFilesDir()=File(dir,"files").apply{mkdirs()}
      override fun getNoBackupFilesDir()=File(dir,"vault").apply{mkdirs()}
      override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {val key=prefix+name;names.add(key);return base.getSharedPreferences(key,mode)}
    }
    val prefs=ctx.getSharedPreferences("classsche_prefs",0)
    val mailPrefs=ctx.getSharedPreferences("school_mail_poll",0)
    val manager=base.getSystemService(android.app.NotificationManager::class.java)
    try{
      NotificationDefaults.initialize(ctx)
      for(minutes in SchoolMailPolling.intervals.filter{it>0})check(SchoolMailPolling.job(ctx,minutes).intervalMillis==minutes*60000L)
      check(SchoolMailPolling.intervalLabel(300)=="每5小时"&&SchoolMailPolling.intervalLabel(600)=="每10小时"&&SchoolMailPolling.intervalLabel(1200)=="每20小时")
      check(SchoolMailPolling.intervalLabel(7200)=="每5天")
      val desktop=BrowserUserAgent.desktop("Mozilla/5.0 (Linux; Android 12; Device; wv) AppleWebKit/537.36 Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36")
      check(!desktop.contains("Android")&&!desktop.contains("Mobile")&&desktop.contains("Windows")&&desktop.contains("Chrome/126"))
      check(CourseNotificationService.isEnabled(ctx)&&ExamOngoingNotificationScheduler.isEnabled(ctx))
      check(SchoolMailPolling.interval(ctx)==15&&SchoolMailPolling.notificationsEnabled(ctx))
      check(NotificationDefaults.consumePermissionPrompt(ctx));check(!NotificationDefaults.consumePermissionPrompt(ctx))
      CourseNotificationService.saveEnabled(ctx,false);SchoolMailPolling.setNotificationsEnabled(ctx,false)
      NotificationDefaults.initialize(ctx)
      check(!CourseNotificationService.isEnabled(ctx)&&!SchoolMailPolling.notificationsEnabled(ctx))
      // Simulate upgrading an older install that left notifications/polling off.
      prefs.edit().clear().putBoolean("notification_enabled",false).putBoolean("legacy_install",true).commit()
      mailPrefs.edit().clear().putInt("minutes",0).commit();NotificationDefaults.initialize(ctx)
      check(!CourseNotificationService.isEnabled(ctx)&&!ExamOngoingNotificationScheduler.isEnabled(ctx))
      check(SchoolMailPolling.interval(ctx)==0&&!SchoolMailPolling.notificationsEnabled(ctx));check(!NotificationDefaults.consumePermissionPrompt(ctx))
      report("Notification defaults: fresh install enabled + one permission prompt; existing explicit or implicit off choices preserved")
      val account=SchoolMailAccount("synthetic@example.invalid","synthetic-notification-code")
      check(SchoolMailAccountStore(ctx).save(account))
      fun page(vararg uids:Long,validity:Long=42)=SchoolMailPage("INBOX",validity,uids.map{SchoolMailSummary(it,"模拟邮件","sender@example.invalid",0,true)},null)
      SchoolMailPolling.observe(ctx,account,page(1,2),false)
      check(!SchoolMailPolling.hasPending(ctx)&&SchoolMailPolling.pendingUids(ctx,account,42).isEmpty())
      mailPrefs.edit().putInt("minutes",15).commit();SchoolMailPolling.setNotificationsEnabled(ctx,true)
      SchoolMailPolling.observe(ctx,account,page(1,2,3,4),true)
      check(SchoolMailPolling.hasPending(ctx)&&SchoolMailPolling.pendingUids(ctx,account,42)==setOf(3L,4L))
      if(android.os.Build.VERSION.SDK_INT<33||base.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED){
        check(manager.activeNotifications.any{it.id==74121}){"Expected local new-mail system notification"}
      }
      SchoolMailPolling.markListViewed(ctx,account,page(3))
      check(SchoolMailPolling.hasPending(ctx)) // unseen later results cannot be swallowed by an older list callback.
      SchoolMailPolling.markListViewed(ctx,account,page(3,4));check(!SchoolMailPolling.hasPending(ctx))
      check(SchoolMailPolling.pendingUids(ctx,account,42)==setOf(3L,4L))
      SchoolMailPolling.markRead(ctx,account,"INBOX",42,3)
      check(SchoolMailPolling.pendingUids(ctx,account,42)==setOf(4L))
      SchoolMailPolling.setNotificationsEnabled(ctx,false)
      SchoolMailPolling.observe(ctx,account,page(4,5),true)
      check(SchoolMailPolling.hasPending(ctx)&&SchoolMailPolling.pendingUids(ctx,account,42)==setOf(4L,5L))
      check(manager.activeNotifications.none{it.id==74121})
      SchoolMailPolling.observe(ctx,account,page(1,2,validity=43),false)
      check(!SchoolMailPolling.hasPending(ctx)&&SchoolMailPolling.pendingUids(ctx,account,43).isEmpty())
      val other=account.copy(address="other@example.invalid")
      check(SchoolMailAccountStore(ctx).save(other));check(!SchoolMailPolling.hasPending(ctx))
      SchoolMailPolling.observe(ctx,account,page(50),true);check(!SchoolMailPolling.hasPending(ctx))
      report("Mail notification/badges: first baseline, persistent new UID markers, list acknowledgement, per-message read, disabled notification keeps badge, UIDVALIDITY/account isolation passed")
    }finally{
      manager.cancel(74121)
      names.forEach{base.getSharedPreferences(it,0).edit().clear().commit()}
      check(dir.canonicalPath.startsWith(base.cacheDir.canonicalPath+File.separator));dir.deleteRecursively()
    }
  }
}
