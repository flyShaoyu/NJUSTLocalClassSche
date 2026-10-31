package com.classsche.mobile

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.HeaderTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.Date

/** Saved app-code authenticates after process restart; live production connections are reused briefly. */
internal class SchoolMailClient(
  private val account:SchoolMailAccount,
  session:Session?=null,
  private val imapHost:String=SchoolMailPolicy.IMAP_HOST,private val imapPort:Int=993,
  private val smtpHost:String=SchoolMailPolicy.SMTP_HOST,private val smtpPort:Int=465
){
  private val reusable=session==null&&imapHost==SchoolMailPolicy.IMAP_HOST&&imapPort==993&&smtpHost==SchoolMailPolicy.SMTP_HOST&&smtpPort==465
  private val session=session?:Session.getInstance(SchoolMailPolicy.properties())
  init{account.validate()}
  private fun <T> withStore(action:(Store)->T):T {if(reusable)return SchoolMailConnections.receiving(account,session,action);val store=session.getStore("imaps");try{store.connect(imapHost,imapPort,account.address,account.secret);return action(store)}finally{runCatching{store.close()}}}
  private fun <T> withTransport(action:(Transport)->T):T {if(reusable)return SchoolMailConnections.sending(account,session,action);val transport=session.getTransport("smtps");try{transport.connect(smtpHost,smtpPort,account.address,account.secret);return action(transport)}finally{runCatching{transport.close()}}}
  fun verify(){try{verifyReceiving()}catch(error:Exception){throw SchoolMailConnectionException("IMAP收信",error)};verifySending()}
  fun verifySending(){try{withTransport{}}catch(error:Exception){throw SchoolMailConnectionException("SMTP发信",error)}}
  fun verifyReceiving(){withStore{check(it.getFolder("INBOX").exists()){"服务器没有收件箱"}}}
  private fun safeFolder(name:String){require(name.length in 1..500&&!name.contains('\r')&&!name.contains('\n')){"邮件夹无效"}}
  fun folders():List<SchoolMailFolder> = withStore { store ->
    buildList {add(SchoolMailFolder("INBOX","收件箱"));for(f in store.defaultFolder.list("*")){
      if(f.fullName.equals("INBOX",true)||f.type and Folder.HOLDS_MESSAGES==0)continue
      val attrs=(f as? IMAPFolder)?.attributes.orEmpty();val label=when{attrs.any{it.equals("\\Sent",true)}->"已发送";attrs.any{it.equals("\\Drafts",true)}->"草稿箱";attrs.any{it.equals("\\Trash",true)}->"已删除";attrs.any{it.equals("\\Junk",true)}->"垃圾邮件";else->f.name}
      if(size<60)add(SchoolMailFolder(f.fullName,label))
    }}
  }
  fun list(folderName:String="INBOX",beforeUid:Long?=null,expectedValidity:Long?=null,limit:Int=30):SchoolMailPage {
    safeFolder(folderName);require(limit in 1..50);require(beforeUid==null||beforeUid>0)
    return withStore {store->val folder=store.getFolder(folderName);try{
      folder.open(Folder.READ_ONLY);val uid=folder as? UIDFolder?:error("服务器不支持稳定消息编号")
      val validity=uid.uidValidity;check(expectedValidity==null||validity==expectedValidity){"邮件夹标识已变化，请刷新列表后重试"}
      var end=folder.messageCount
      if(beforeUid!=null){var low=1;var high=end;end=0;while(low<=high){val middle=(low+high)/2;val value=uid.getUID(folder.getMessage(middle));if(value<beforeUid){end=middle;low=middle+1}else high=middle-1}}
      if(end==0)return@withStore SchoolMailPage(folderName,validity,emptyList(),null)
      val start=maxOf(1,end-limit+1);val messages=folder.getMessages(start,end)
      val profile=FetchProfile().apply{add(FetchProfile.Item.ENVELOPE);add(FetchProfile.Item.FLAGS);add(UIDFolder.FetchProfileItem.UID)}
      folder.fetch(messages,profile)
      val items=messages.filterNot{it.isExpunged}.map {m->SchoolMailSummary(uid.getUID(m),m.subject.orEmpty().take(1000),m.from.orEmpty().joinToString(", "){(it as? InternetAddress)?.toUnicodeString()?:it.toString()}.take(1000),m.sentDate?.time?:m.receivedDate?.time?:0,!m.isSet(Flags.Flag.SEEN))}.sortedByDescending{it.uid}
      SchoolMailPage(folderName,validity,items,if(start>1)items.lastOrNull()?.uid else null)
    }finally{if(folder.isOpen)runCatching{folder.close(false)}}}
  }
  fun read(folderName:String,uidValue:Long,validity:Long):SchoolMailContent {
    safeFolder(folderName);require(uidValue>0&&validity>0)
    return withStore {store->val folder=store.getFolder(folderName);try{
      folder.open(Folder.READ_ONLY);val ids=folder as? UIDFolder?:error("服务器不支持稳定消息编号");check(ids.uidValidity==validity){"邮件夹标识已变化，请刷新后重试"}
      val message=ids.getMessageByUID(uidValue)?:error("邮件已被移动或删除，请刷新列表")
      val body=SchoolMailMime.read(message)
      val reply=message.replyTo.orEmpty().filterIsInstance<InternetAddress>().joinToString(", "){it.address}
      SchoolMailContent(folderName,validity,uidValue,message.subject.orEmpty().take(1000),message.from.orEmpty().joinToString(", "){(it as? InternetAddress)?.toUnicodeString()?:it.toString()}.take(1000),message.allRecipients.orEmpty().joinToString(", "){(it as? InternetAddress)?.toUnicodeString()?:it.toString()}.take(4000),reply,message.sentDate?.time?:message.receivedDate?.time?:0,body.text,body.attachments,body.truncated,message.getHeader("Message-ID")?.firstOrNull().orEmpty().take(998),message.getHeader("References")?.joinToString(" ").orEmpty().takeLast(4000))
    }finally{if(folder.isOpen)runCatching{folder.close(false)}}}
  }
  data class SendResult(val state:String,val detail:String)
  fun send(id:String,draft:SchoolMailDraft,outbox:SchoolMailOutbox):SendResult {
    draft.validate();require(id.matches(Regex("[a-f0-9-]{36}"))){"草稿标识无效"}
    val messageId="<$id@${account.address.substringAfter('@')}>"
    val message=MimeMessage(session).apply{
      setFrom(InternetAddress(account.address));setRecipients(Message.RecipientType.TO,SchoolMailPolicy.addresses(draft.to));setSubject(draft.subject,"UTF-8");setText(draft.text,"UTF-8");sentDate=Date()
      if(draft.replyMessageId.isNotBlank()){setHeader("In-Reply-To",draft.replyMessageId);setHeader("References",(draft.references+" "+draft.replyMessageId).trim().takeLast(4000))}
      saveChanges();setHeader("Message-ID",messageId)
    }
    outbox.begin(id,account.address,messageId)
    var submitted=false
    try{
      withTransport{transport->submitted=true;transport.sendMessage(message,message.allRecipients)}
    }catch(error:Exception){
      val partial=(error as? SendFailedException)?.validSentAddresses?.isNotEmpty()==true
      val definite=error is AuthenticationFailedException||!submitted||error is org.eclipse.angus.mail.smtp.SMTPSendFailedException&&error.returnCode in 400..599&&!partial
      val state=if(partial)"partial" else if(definite)"failed" else "unknown"
      val detail=if(partial)"部分收件人可能已收到，请逐个核对，勿整封重发" else if(definite)"发送失败："+SchoolMailErrors.describe(error) else "发送结果未知，可能已被服务器接收；请查已发送或向收件人核对，勿直接重发"
      outbox.update(id,state,detail);return SendResult(state,detail)
    }
    // SMTP acceptance is distinct from recipient delivery. Never automatically send again.
    outbox.update(id,"accepted","邮件已被SMTP服务器受理，不代表已送达收件人")
    val copied=runCatching{withStore{store->
      val sent=store.defaultFolder.list("*").firstOrNull{f->(f as? IMAPFolder)?.attributes.orEmpty().any{it.equals("\\Sent",true)}||f.fullName in listOf("Sent","Sent Messages","已发送") }?:return@withStore false
      try{sent.open(Folder.READ_WRITE);if(sent.search(HeaderTerm("Message-ID",messageId)).isEmpty())sent.appendMessages(arrayOf(message));true}finally{if(sent.isOpen)runCatching{sent.close(false)}}
    }}.getOrDefault(false)
    val detail=if(copied)"SMTP服务器已受理，已核对或保存已发送副本；不代表收件人已阅读" else "SMTP服务器已受理；已发送副本未能确认，请勿因此重发"
    outbox.update(id,"accepted",detail);return SendResult("accepted",detail)
  }
}
internal class SchoolMailConnectionException(val stage:String,cause:Exception):Exception(stage,cause)
internal object SchoolMailErrors {
  fun describe(error:Throwable):String {
    val chain=generateSequence(error){it.cause}.take(8).toList()
    val prefix=(error as? SchoolMailConnectionException)?.stage?.plus("：").orEmpty()
    return prefix+when{
      chain.any{it is AuthenticationFailedException}->"认证失败，请检查完整邮箱、客户端授权码及学校是否开放IMAP/SMTP；普通网页登录密码可能不适用"
      chain.any{it is javax.net.ssl.SSLException}->"TLS证书或安全连接校验失败，未关闭证书验证"
      chain.any{it is java.net.SocketTimeoutException}->"邮件服务器连接超时，请稍后手动刷新"
      chain.any{it is java.net.UnknownHostException||it is java.net.ConnectException}->"无法连接邮件服务器，请检查网络"
      error is IllegalArgumentException||error is IllegalStateException->error.message?.takeIf{!it.contains("@")}?:"邮箱操作未完成，请检查配置后重试"
      else->"邮件服务暂不可用（${error.javaClass.simpleName}）；请检查客户端服务开关或稍后刷新"
    }
  }
}
