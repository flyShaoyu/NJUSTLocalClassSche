package com.classsche.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.classsche.mobile.databinding.ActivitySchoolMailBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SchoolMailSettingsActivity:AppCompatActivity(){
  private lateinit var binding:ActivitySchoolMailBinding
  private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
  override fun onCreate(state:Bundle?){
    super.onCreate(state);NotificationDefaults.initialize(this)
    binding=ActivitySchoolMailBinding.inflate(layoutInflater);setContentView(binding.root)
    SecondaryPageToolbar.bind(binding.root,binding.mailToolbar);binding.mailToolbar.title="邮件更新与通知"
    binding.mailToolbar.setNavigationOnClickListener{finish()}
  }
  override fun onResume(){super.onResume();if(::binding.isInitialized)render()}
  private fun paragraph(value:String,size:Float=13f)=TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(82,107,132));setPadding(dp(4),dp(10),dp(4),dp(10))}
  private fun button(value:String,action:()->Unit)=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply{text=value;textSize=13f;setOnClickListener{action()}}
  private fun render(){
    val content=binding.mailContent;content.removeAllViews()
    val interval=SchoolMailPolling.interval(this)
    content.addView(paragraph("邮件更新间隔",16f))
    content.addView(BluePageUi.selector(this,SchoolMailPolling.intervalLabel(interval)){
      val values=SchoolMailPolling.intervals
      BluePageUi.choices(this,"邮件更新间隔",values.map(SchoolMailPolling::intervalLabel),values.indexOf(interval)){index->
        runCatching{SchoolMailPolling.configure(this,values[index])}.onFailure{showError(it)}
        render()
      }
    }.apply{tag="mailUpdateInterval"})
    content.addView(paragraph("自动检查收件箱，有新邮件时更新红点。系统省电或网络不稳定时可能晚一些，也可以在邮件页手动刷新。"))
    val toggle=SwitchMaterial(this).apply{
      text="新邮件通知";textSize=14f;tag="mailNotificationSwitch";isChecked=SchoolMailPolling.notificationsEnabled(this@SchoolMailSettingsActivity)
      setOnCheckedChangeListener{_,enabled->
        runCatching{SchoolMailPolling.setNotificationsEnabled(this@SchoolMailSettingsActivity,enabled)}.onFailure{showError(it)}
        if(enabled&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),751)
      }
    };content.addView(toggle,LinearLayout.LayoutParams(-1,-2))
    content.addView(paragraph("开启后，发现新邮件会在手机通知栏提醒你，只显示新信数量。关闭通知不会影响收信和 App 内的红点。"))
    if(!NotificationManagerCompat.from(this).areNotificationsEnabled())content.addView(button("去开启系统通知权限"){
      startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
    })
    if(SchoolMailAccountStore(this).load()==null)content.addView(paragraph("还没配置邮箱，先去邮件管理填好邮箱和客户端专用密码；设置会在配置完成后生效。"))
    content.addView(paragraph(SchoolMailPolling.lastCheck(this),12f))
  }
  private fun showError(error:Throwable){AlertDialog.Builder(this).setMessage(SchoolMailErrors.describe(error)).setPositiveButton("知道了",null).show()}
}
