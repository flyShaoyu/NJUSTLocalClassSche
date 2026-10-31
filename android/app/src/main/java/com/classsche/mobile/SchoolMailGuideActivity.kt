package com.classsche.mobile

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivitySchoolMailBinding
import com.google.android.material.button.MaterialButton

/** Bundled, flattened screenshots: never opens the user's own mailbox or exposes its secrets. */
class SchoolMailGuideActivity:AppCompatActivity(){
  override fun onCreate(state:Bundle?){
    super.onCreate(state)
    val b=ActivitySchoolMailBinding.inflate(layoutInflater);setContentView(b.root)
    SecondaryPageToolbar.bind(b.root,b.mailToolbar);b.mailToolbar.title="邮箱使用引导";b.mailToolbar.setNavigationOnClickListener{finish()}
    fun text(value:String,size:Float=13f){b.mailContent.addView(TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(82,107,132));setPadding(4,16,4,16)})}
    fun step(title:String,body:String,image:Int){
      text(title,16f);text(body)
      b.mailContent.addView(ImageView(this).apply{
        setImageResource(image);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=title+"，点击放大"
        setOnClickListener{
          val zoom=android.webkit.WebView(this@SchoolMailGuideActivity).apply{
            settings.javaScriptEnabled=false;settings.builtInZoomControls=true;settings.displayZoomControls=false
            settings.useWideViewPort=true;settings.loadWithOverviewMode=true
            settings.allowFileAccess=false;settings.allowContentAccess=false;settings.blockNetworkLoads=true
            val data=resources.openRawResource(image).use{android.util.Base64.encodeToString(it.readBytes(),android.util.Base64.NO_WRAP)}
            loadDataWithBaseURL(null,"<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=6'><body style='margin:0'><img style='width:100%;height:auto' alt='邮箱设置步骤' src='data:image/webp;base64,$data'></body>","text/html","utf-8",null)
          }
          val dialog=AlertDialog.Builder(this@SchoolMailGuideActivity).setTitle(title).setView(zoom).setPositiveButton("看完了",null).create()
          dialog.setOnDismissListener{zoom.destroy()};dialog.show();dialog.window?.setLayout(-1,(resources.displayMetrics.heightPixels*.85).toInt())
        }
      },LinearLayout.LayoutParams(-1,-2))
    }
    text("第一次连邮箱？跟着做一遍就好",17f)
    text("建议先用电脑打开学校邮箱网页版，用微信扫码登录。手机也可以试试浏览器的“电脑模式”。下面的图点一下能放大，双指缩放就能看细节。")
    b.mailContent.addView(MaterialButton(this).apply{text="打开学校邮箱网页版";textSize=13f;setOnClickListener{startActivity(Intent(this@SchoolMailGuideActivity,CampusBrowserActivity::class.java).putExtra("url","https://mail.njust.edu.cn/").putExtra("desktop_mode",true))}})
    step("1. 先点顶部的“设置”","登录进去后，找到顶部的“设置”，图里红框标出来了。",R.drawable.mail_guide_1)
    step("2. 找到“客户端设置”","在邮箱设置这一排标签里，点“客户端设置”。不用下载 Foxmail，我们只借用这里的收发服务。",R.drawable.mail_guide_2)
    step("3. 打开 IMAP/SMTP，再保存","勾上“开启IMAP/SMTP服务”。下面的收取范围、文件夹和已发送保存选项按需要选；如果想看到更早的邮件，记得看看收取时间范围。改完点左下角“保存更改”。不用另外开启 POP。",R.drawable.mail_guide_3)
    step("4. 再切到“账户”","接下来要生成 App 登录用的密码。回到上面的标签栏，点“账户”。",R.drawable.mail_guide_4)
    step("5. 找到“生成新密码”","往下找到“客户端专用密码”，点“生成新密码”。如果网页要求微信确认，就按网页提示操作。",R.drawable.mail_guide_5)
    step("6. 复制密码，回 App 填进去","弹窗里那串“客户端密码”就是 App 要填的授权码，通常只显示这一次。复制好再关闭弹窗；用途可以写“课表App”，方便以后认出来。这里的密码不是微信密码，也不是教务密码。",R.drawable.mail_guide_6)
    text("7. 最后再保存一下",16f)
    text("回到网页点“保存更改”（前一张图标了 9 的位置），然后回 App 的邮箱设置：填完整邮箱地址和刚复制的客户端专用密码，点“验证连接并保存”。连上以后，就可以收发邮件了。")
    text("收件提醒怎么开？",16f)
    text("在 App 设置或邮件页打开“邮件更新与通知”，选检查间隔、打开新邮件通知。它会定时检查，不是秒到提醒；手机还要允许 App 发通知。")
    text("图里的个人资料和密码已经遮住。你自己的客户端专用密码别发给别人；如果丢了，在网页重新生成一个就好。",12f)
  }
}
