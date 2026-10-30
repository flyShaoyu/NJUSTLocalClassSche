package com.classsche.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object SchoolMailRuntime {
  val executor=Executors.newSingleThreadExecutor()
  private val initialized=AtomicBoolean(false)
  fun init(context:Context){if(initialized.compareAndSet(false,true))SchoolMailOutbox(context.applicationContext).use{it.recoverInterrupted()}}
}
internal object SchoolMailPolling {
  private const val JOB=74120
  private const val NOTICE=74121
  private fun prefs(c:Context)=c.getSharedPreferences("school_mail_poll",Context.MODE_PRIVATE)
  fun interval(c:Context)=prefs(c).getInt("minutes",0)
  data class Decision(val newest:Long,val count:Int)
  fun decide(oldValidity:Long,oldUid:Long,page:SchoolMailPage):Decision{
    val same=oldValidity==page.validity
    val newest=page.items.maxOfOrNull{it.uid}?:0
    return Decision(if(same)maxOf(oldUid,newest)else newest,if(same)page.items.count{it.uid>oldUid}else 0)
  }
  fun lastCheck(c:Context):String{val p=prefs(c);val at=p.getLong("last_check",0);return if(at==0L)"尚无自动轮询记录" else  java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))+" · "+p.getString("last_result","")}
  fun record(c:Context,account:SchoolMailAccount,result:String){if(SchoolMailAccountStore(c).load()==account)prefs(c).edit().putLong("last_check",System.currentTimeMillis()).putString("last_result",result).apply()}

  fun configure(c:Context,minutes:Int){
    require(minutes in listOf(0,15,30,60));val previous=interval(c)
    check(prefs(c).edit().putInt("minutes",minutes).commit())
    try{schedule(c);if(minutes==0)c.getSystemService(NotificationManager::class.java).cancel(NOTICE)}
    catch(error:Exception){prefs(c).edit().putInt("minutes",previous).commit();runCatching{schedule(c)};throw error}
  }
  fun schedule(c:Context){
    val scheduler=c.getSystemService(JobScheduler::class.java);scheduler.cancel(JOB)
    val minutes=interval(c);if(minutes==0||SchoolMailAccountStore(c).load()==null)return
    val result=scheduler.schedule(job(c,minutes))
    check(result==JobScheduler.RESULT_SUCCESS){"系统未能开启轮询，请稍后重试"}
  }
  fun job(c:Context,minutes:Int):JobInfo{require(minutes in listOf(15,30,60));return JobInfo.Builder(JOB,ComponentName(c,SchoolMailPollService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(minutes*60000L).build()}
  fun clearCheckpoint(c:Context){prefs(c).edit().remove("account").remove("validity").remove("uid").remove("last_check").remove("last_result").apply();c.getSystemService(NotificationManager::class.java).cancel(NOTICE)}
  @Synchronized fun observe(c:Context,account:SchoolMailAccount,page:SchoolMailPage,notify:Boolean){
    if(page.folder!="INBOX"||SchoolMailAccountStore(c).load()!=account)return
    val p=prefs(c);val sameAccount=p.getString("account",null)==SchoolMailPolicy.accountKey(account.address)
    val decision=decide(if(sameAccount)p.getLong("validity",-1)else -1,p.getLong("uid",0),page)
    val count=decision.count
    check(p.edit().putString("account",SchoolMailPolicy.accountKey(account.address)).putLong("validity",page.validity).putLong("uid",decision.newest).commit())
    if(notify&&count>0&&interval(c)>0){
      val manager=c.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel("school_mail","学校邮箱新邮件",NotificationManager.IMPORTANCE_DEFAULT))
      val pending=PendingIntent.getActivity(c,0,Intent(c,SchoolMailActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val notification=NotificationCompat.Builder(c,"school_mail").setSmallIcon(R.drawable.ic_home_mail)
        .setContentTitle("学校邮箱收到新邮件").setContentText("打开查看（本次检测到${if(count==page.items.size&&page.nextBefore!=null)"至少" else ""}${count}封）")
        .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
      runCatching{manager.notify(NOTICE,notification)}
    }
  }
}
class SchoolMailPollService:JobService(){
  private val executor=Executors.newSingleThreadExecutor()
  private val generation=java.util.concurrent.atomic.AtomicInteger(0)
  override fun onStartJob(params:JobParameters):Boolean{
    val ticket=generation.incrementAndGet()
    executor.execute{
      var account:SchoolMailAccount?=null
      try{
        account=SchoolMailAccountStore(this).load()
        if(account!=null&&SchoolMailPolling.interval(this)>0&&generation.get()==ticket){
          val page=SchoolMailClient(account).list()
          if(generation.get()==ticket&&SchoolMailPolling.interval(this)>0){SchoolMailPolling.observe(this,account,page,true);SchoolMailPolling.record(this,account,"收件检查成功") }
        }
      }catch(error:Exception){if(generation.get()==ticket)account?.let{SchoolMailPolling.record(this,it,SchoolMailErrors.describe(error))}}
      finally{if(generation.get()==ticket)jobFinished(params,false)}
    };return true
  }
  override fun onStopJob(params:JobParameters):Boolean{generation.incrementAndGet();return false}
  override fun onDestroy(){generation.incrementAndGet();executor.shutdownNow();super.onDestroy()}
}
