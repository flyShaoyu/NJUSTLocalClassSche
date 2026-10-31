package com.classsche.mobile

import jakarta.mail.internet.InternetAddress
import java.util.Properties

internal data class SchoolMailAccount(val address:String,val secret:String) {
  fun validate(){ require(address.length in 3..254 && secret.isNotBlank() && secret.length<=4096){"请填写完整邮箱地址和客户端授权码"};SchoolMailPolicy.addresses(address).also{require(it.size==1&&it[0].address==address){"账号须为完整邮箱地址"}} }
  override fun toString()="SchoolMailAccount([redacted])"
}
internal data class SchoolMailFolder(val name:String,val label:String)
internal data class SchoolMailSummary(val uid:Long,val subject:String,val from:String,val date:Long,val unread:Boolean)
internal data class SchoolMailPage(val folder:String,val validity:Long,val items:List<SchoolMailSummary>,val nextBefore:Long?)
internal data class SchoolMailContent(val folder:String,val validity:Long,val uid:Long,val subject:String,val from:String,val recipients:String,val replyTo:String,val date:Long,val text:String,val attachments:List<String>,val truncated:Boolean,val messageId:String,val references:String)
internal data class SchoolMailDraft(val to:String,val subject:String,val text:String,val replyMessageId:String="",val references:String="") {
  fun validate(){SchoolMailPolicy.addresses(to);require(subject.isNotBlank()&&subject.length<=500&&!subject.contains('\r')&&!subject.contains('\n')){"主题不能为空，且不能包含换行"};require(text.isNotBlank()&&text.length<=100000){"正文须为1至100000字符"};require(replyMessageId.length<=998&&references.length<=4000&&!replyMessageId.contains('\r')&&!replyMessageId.contains('\n')&&!references.contains('\r')&&!references.contains('\n')){"回复标识无效"}}
  override fun toString()="SchoolMailDraft([redacted])"
}
internal object SchoolMailPolicy {
  fun accountKey(address:String)=java.security.MessageDigest.getInstance("SHA-256").digest(address.trim().lowercase(java.util.Locale.ROOT).toByteArray()).joinToString(""){"%02x".format(it)}
  const val IMAP_HOST="imap.exmail.qq.com"
  const val SMTP_HOST="smtp.exmail.qq.com"
  fun addresses(raw:String):Array<InternetAddress> {
    require(raw.isNotBlank()&&raw.length<=4000&&!raw.contains('\r')&&!raw.contains('\n')){"请填写有效收件人邮箱"}
    val result=try{InternetAddress.parse(raw.replace('；',';').replace(';',','),true)}catch(_:Exception){throw IllegalArgumentException("收件人邮箱格式不正确")}
    require(result.size in 1..20&&result.none{it.isGroup}){"每封邮件支持1至20个独立收件人"}
    for(address in result){try{address.validate()}catch(_:Exception){throw IllegalArgumentException("收件人邮箱格式不正确")};require(address.address.contains('@')&&address.address.substringAfter('@').contains('.')){"请填写完整邮箱地址"}}
    return result
  }
  fun properties()=Properties().apply{
    setProperty("mail.imaps.host",IMAP_HOST);setProperty("mail.imaps.port","993")
    setProperty("mail.smtps.host",SMTP_HOST);setProperty("mail.smtps.port","465")
    for(protocol in listOf("imaps","smtps")){
      setProperty("mail.$protocol.ssl.enable","true");setProperty("mail.$protocol.ssl.checkserveridentity","true")
      setProperty("mail.$protocol.connectiontimeout","15000");setProperty("mail.$protocol.timeout","25000");setProperty("mail.$protocol.writetimeout","25000")
      setProperty("mail.$protocol.ssl.protocols","TLSv1.2 TLSv1.3")
    }
    setProperty("mail.imaps.peek","true");setProperty("mail.imaps.fetchsize","16384")
    setProperty("mail.smtps.auth","true");setProperty("mail.smtps.sendpartial","false");setProperty("mail.debug","false")
  }
}
internal class SchoolMailAccountStore(context:android.content.Context){
  private val vault=LibraryCredentialStore(context,"school_mail_credentials")
  fun load():SchoolMailAccount?=vault.load()?.let{SchoolMailAccount(it.username,it.password)}
  fun save(account:SchoolMailAccount):Boolean{account.validate();val saved=vault.save(LibraryCredentialStore.Credentials(account.address,account.secret,"email"));if(saved)SchoolMailConnections.accountSaved(account);return saved}
  fun clear(){SchoolMailConnections.invalidate();vault.clear()}
}
