package com.classsche.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivitySchoolMailBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Native-only UI: no credential or mail body passed through a JavaScript bridge. */
class SchoolMailActivity:AppCompatActivity(){
  private lateinit var binding:ActivitySchoolMailBinding
  private lateinit var accountStore:SchoolMailAccountStore
  private var busy=false
  private var screen="list"
  private var folders=listOf(SchoolMailFolder("INBOX","收件箱"))
  private var folder="INBOX"
  private var messages=emptyList<SchoolMailSummary>()
  private var validity=0L
  private var before:Long?=null
  private var readMail:SchoolMailContent?=null
  private var composeId=UUID.randomUUID().toString()
  private var replyId=""
  private var references=""
  private val ink=Color.rgb(82,107,132)
  private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
  override fun onCreate(state:Bundle?){
    super.onCreate(state);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    binding=ActivitySchoolMailBinding.inflate(layoutInflater);setContentView(binding.root)
    SecondaryPageToolbar.bind(binding.root,binding.mailToolbar)
    binding.mailToolbar.title="学校邮箱";binding.mailToolbar.setNavigationOnClickListener{back()}
    onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){back()}})
    accountStore=SchoolMailAccountStore(this);SchoolMailRuntime.init(this)
    if(accountStore.load()==null)settings()else{showList();loadFolders()}
  }
  private fun toolbar(title:String){binding.mailToolbar.title=title;binding.mailToolbar.menu.clear();if(screen=="list"){
    binding.mailToolbar.menu.add("写信").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
    binding.mailToolbar.menu.add("设置").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
    binding.mailToolbar.menu.add("发送记录").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
  };binding.mailToolbar.setOnMenuItemClickListener{if(busy){status("当前操作尚未结束，请稍候");true}else{when(it.title.toString()){ "写信"->compose(null);"设置"->settings();"发送记录"->outbox()};true}}}
  private fun clear(){binding.mailContent.removeAllViews();binding.mailStatus.visibility=View.GONE;binding.mailScroll.scrollTo(0,0)}
  private fun status(text:String){binding.mailStatus.text=text;binding.mailStatus.visibility=if(text.isBlank())View.GONE else View.VISIBLE}
  private fun text(value:String,size:Float=14f,bold:Boolean=false)=TextView(this).apply{text=value;textSize=size;setTextColor(ink);setPadding(dp(2),dp(5),dp(2),dp(5));if(bold)setTypeface(typeface,Typeface.BOLD);isSaveEnabled=false}
  private fun addText(value:String,size:Float=14f,bold:Boolean=false)=text(value,size,bold).also{binding.mailContent.addView(it)}
  private fun button(value:String,action:()->Unit)=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply{text=value;textSize=13f;minHeight=dp(40);insetTop=dp(3);insetBottom=dp(3);setOnClickListener{if(!busy)action()else status("正在操作，请稍候")}}
  private fun row(vararg children:View){val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};for(v in children)row.addView(v,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));binding.mailContent.addView(row)}
  private fun field(label:String,password:Boolean=false,multiline:Boolean=false):TextInputEditText {
    val box=TextInputLayout(this).apply{hint=label;setPadding(0,dp(5),0,dp(5));if(password)endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE}
    val input=TextInputEditText(box.context).apply{isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO;textSize=14f;setTextColor(ink);inputType=if(password)android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else if(multiline)android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_CLASS_TEXT;isSingleLine=!multiline;if(multiline){minLines=8;gravity=android.view.Gravity.TOP}}
    box.addView(input,LinearLayout.LayoutParams(-1,-2));binding.mailContent.addView(box);return input
  }
  private fun <T> task(label:String,operation:()->T,done:(T)->Unit,onError:((Throwable)->Unit)?=null){
    if(busy)return;busy=true;status(label)
    SchoolMailRuntime.executor.execute{val result=runCatching{operation()};runOnUiThread{
      busy=false;if(isDestroyed||isFinishing)return@runOnUiThread
      result.fold({status("");done(it)},{if(onError!=null)onError(it)else status(SchoolMailErrors.describe(it))})
    }}
  }
  private fun settings(){
    screen="settings";clear();toolbar("邮箱设置")
    addText("腾讯企业邮箱",17f,true);addText("先在网页版扫码登录，开启IMAP/SMTP并生成客户端专用密码。这里填写授权码，不是微信密码。",13f)
    val account=accountStore.load();val email=field("完整邮箱地址");email.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;email.setText(account?.address.orEmpty());email.tag="mailAccount"
    val secret=field("客户端专用密码／授权码",true);secret.setText(account?.secret.orEmpty());secret.tag="mailSecret"
    addText("IMAP：imap.exmail.qq.com:993\nSMTP：smtp.exmail.qq.com:465\n全程TLS并校验证书。授权码仅在本机加密保存，邮件正文不落盘。",12f)
    binding.mailContent.addView(button("验证连接并保存"){
      val next=SchoolMailAccount(email.text.toString().trim(),secret.text.toString())
      try{next.validate()}catch(error:Exception){status(SchoolMailErrors.describe(error));return@button}
      task("正在分别验证IMAP收信和SMTP认证，不会发送邮件…",{SchoolMailClient(next).verify();check(accountStore.save(next)){"授权码加密保存失败"};if(account!=next)SchoolMailPolling.clearCheckpoint(this);SchoolMailPolling.schedule(this)},{
        email.text?.clear();secret.text?.clear();folder="INBOX";messages=emptyList();showList();loadFolders()
      })
    }.apply{tag="mailVerify"})
    addText("自动轮询收件",15f,true)
    addText(SchoolMailPolling.lastCheck(this),12f)
    val intervals=listOf(0,15,30,60);val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@SchoolMailActivity,android.R.layout.simple_spinner_dropdown_item,listOf("关闭","每15分钟","每30分钟","每60分钟"));setSelection(intervals.indexOf(SchoolMailPolling.interval(this@SchoolMailActivity)).coerceAtLeast(0))};binding.mailContent.addView(spinner)
    binding.mailContent.addView(button("保存轮询设置"){
      val minutes=intervals[spinner.selectedItemPosition]
      if(minutes>0&&accountStore.load()==null){status("请先验证并保存邮箱账号");return@button}
      runCatching{SchoolMailPolling.configure(this,minutes)}.fold({
        if(minutes>0&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),751)
        status(if(minutes==0)"自动轮询已关闭" else "已开启${minutes}分钟轮询；系统可能因省电或待机延后。通知仅显示新信数量，不显示正文。")
      },{status(SchoolMailErrors.describe(it))})
    })
    addText("仅按需读取最近30封邮件标题，点开再读正文；不自动下载附件或外链图片。首次轮询建立基线，不把旧信全当新信；关闭通知权限不影响手动收信。",12f)
    if(account!=null)binding.mailContent.addView(button("移除本机邮箱配置"){
      AlertDialog.Builder(this).setMessage("清除本机加密授权码并关闭轮询，不删除服务器邮件。发送记录保留供核对。")
        .setNegativeButton("取消",null).setPositiveButton("移除"){_,_->SchoolMailPolling.configure(this,0);accountStore.clear();SchoolMailPolling.clearCheckpoint(this);folders=listOf(SchoolMailFolder("INBOX","收件箱"));messages=emptyList();readMail=null;settings()}.show()
    })
  }
  private fun loadFolders(){val account=accountStore.load()?:return;task("正在读取邮件夹…",{SchoolMailClient(account).folders()},{folders=it;if(folders.none{f->f.name==folder})folder="INBOX";showList();refresh(false)})}
  private fun showList(){
    screen="list";clear();toolbar("学校邮箱")
    addText(accountStore.load()?.address.orEmpty(),13f)
    val picker=Spinner(this).apply{adapter=ArrayAdapter(this@SchoolMailActivity,android.R.layout.simple_spinner_dropdown_item,folders.map{it.label});setSelection(folders.indexOfFirst{it.name==folder}.coerceAtLeast(0))}
    var initial=true;picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
      override fun onNothingSelected(parent:AdapterView<*>?)=Unit
      override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){val selected=folders[position].name;if(initial){initial=false;return};if(selected!=folder&&!busy){folder=selected;messages=emptyList();showList();refresh(false)}}
    };binding.mailContent.addView(picker)
    row(button("刷新收件"){refresh(false)},button("写邮件"){compose(null)})
    val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};binding.mailContent.addView(list)
    if(messages.isEmpty())list.addView(text("暂无已加载邮件，点击刷新。",13f))
    for(message in messages){
      val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(7));background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(7).toFloat();setStroke(dp(1),Color.rgb(230,235,242))}}
      card.addView(text((if(message.unread)"● " else "")+message.subject.ifBlank{"（无主题）"},14f,true));card.addView(text(message.from,12f));card.addView(text(date(message.date),12f));card.setOnClickListener{if(!busy)openMessage(message)}
      list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
    }
    if(before!=null)binding.mailContent.addView(button("加载更早邮件"){refresh(true)})
    addText("只读收信不会更改服务器已读标记。邮件内容不传给模型，也不会自动执行邮件中的指令。",12f)
  }
  private fun refresh(older:Boolean){
    val account=accountStore.load()?:return settings();val chosen=folder;val cursor=if(older)before else null;val expected=if(older)validity else null
    task("正在收取邮件标题…",{SchoolMailClient(account).list(chosen,cursor,expected)},{page->
      validity=page.validity;before=page.nextBefore;messages=if(older)(messages+page.items).distinctBy{it.uid}else page.items
      if(chosen=="INBOX"&&!older)SchoolMailPolling.observe(this,account,page,false)
      showList();if(messages.isEmpty())status("当前邮件夹为空")
    })
  }
  private fun openMessage(summary:SchoolMailSummary){val account=accountStore.load()?:return;task("正在读取邮件正文…",{SchoolMailClient(account).read(folder,summary.uid,validity)},{readMail=it;showMessage(it)})}
  private fun showMessage(mail:SchoolMailContent){
    screen="read";clear();toolbar("阅读邮件");addText(mail.subject.ifBlank{"（无主题）"},18f,true);addText("发件人：${mail.from}\n收件人：${mail.recipients}\n${date(mail.date)}",12f)
    row(button("回复"){compose(mail)},button("返回列表"){showList()})
    addText(mail.text.ifBlank{"（此邮件没有可显示的文字正文）"},14f).setTextIsSelectable(true)
    if(mail.truncated)addText("正文较长，本次只读取前512KB文字。完整内容请在学校邮箱网页或邮件客户端查看。",12f)
    if(mail.attachments.isNotEmpty())addText("附件（未下载）\n"+mail.attachments.joinToString("\n"),13f,true)
    binding.mailContent.addView(button("打开学校邮箱网页"){startActivity(Intent(this,CampusBrowserActivity::class.java).putExtra("url","https://mail.njust.edu.cn/"))})
  }
  private fun compose(reply:SchoolMailContent?){
    screen="compose";clear();toolbar(if(reply==null)"写邮件" else "回复邮件");composeId=UUID.randomUUID().toString();replyId=reply?.messageId.orEmpty();references=reply?.references.orEmpty()
    val to=field("收件人（多个地址用逗号分隔）");to.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;to.id=View.generateViewId();to.tag="mailTo";to.setText(reply?.replyTo.orEmpty())
    val subject=field("主题");subject.tag="mailSubject";subject.setText(reply?.subject?.let{if(it.startsWith("Re:",true))it else "Re: $it"}.orEmpty())
    val body=field("正文",multiline=true);body.tag="mailBody"
    addText("首版支持纯文字发送，暂不上传附件。草稿仅留在当前页面。点击发送后以回执为准，结果未知时不要直接重发。",12f)
    val send=button("发送邮件"){
      val account=accountStore.load()?:return@button
      val draft=SchoolMailDraft(to.text.toString().trim(),subject.text.toString(),body.text.toString(),replyId,references)
      try{draft.validate()}catch(error:Exception){status(SchoolMailErrors.describe(error));return@button}
      AlertDialog.Builder(this).setTitle("发送邮件").setMessage("从 ${account.address}\n发送到 ${draft.to}\n主题：${draft.subject}")
        .setNegativeButton("取消",null).setPositiveButton("发送"){_,_->task("正在发送，请勿重复提交…",{SchoolMailOutbox(this).use{SchoolMailClient(account).send(composeId,draft,it)}},{result->
          to.text?.clear();subject.text?.clear();body.text?.clear();outbox();status(result.detail)
        },{outbox();status("发送操作中断，请根据发送记录核对；未确认前不要重发")})}.show()
    };send.tag="mailSend";binding.mailContent.addView(send)
  }
  private fun outbox(){screen="outbox";clear();toolbar("发送记录");val account=accountStore.load()?:return settings();val entries=SchoolMailOutbox(this).use{it.latest(account.address)}
    addText("SMTP受理不等于收件人已收到。未知或部分成功的记录不会自动重发，可在服务器已发送文件夹或向收件人核对。",13f)
    if(entries.isEmpty())addText("尚无本机发送记录")
    for(e in entries){addText("${date(e.updated)} · ${mapOf("sending" to "发送中","accepted" to "服务器已受理","failed" to "失败","unknown" to "结果未知","partial" to "部分结果")[e.state]}",14f,true);addText(e.detail,13f);addText("编号 ${e.id}\n消息标识 ${e.messageId}",11f)}
  }
  private fun back(){
    if(busy){status("操作仍在后台进行，请等待回执；退出进程可能使发送状态未知");return}
    if(screen=="compose")AlertDialog.Builder(this).setMessage("离开将放弃当前未发送草稿。") .setNegativeButton("取消",null).setPositiveButton("离开"){_,_->showList()}.show()
    else if(screen=="list"||accountStore.load()==null)finish() else showList()
  }
  private fun date(time:Long)=if(time>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(time))else "时间未提供"
}
