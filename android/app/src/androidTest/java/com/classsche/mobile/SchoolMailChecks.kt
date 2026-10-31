package com.classsche.mobile

import android.content.Context
import jakarta.mail.*
import jakarta.mail.internet.*
import java.io.ByteArrayInputStream
import java.util.UUID

/** In-process protocol providers: no socket, real credentials or real email ever sent. */
internal object MailFixture {
  var receivingConnections=0;var sendingConnections=0
  var connectFails=false;var uncertain=false;var partial=false;var copyFails=false;var sends=0;var appends=0;var closed=0
  val inbox=mutableListOf<Message>();val sent=mutableListOf<Message>()
  fun session():Session {
    val session=Session.getInstance(SchoolMailPolicy.properties())
    session.setProvider(Provider(Provider.Type.STORE,"imaps",FixtureMailStore::class.java.name,"test","1"))
    session.setProvider(Provider(Provider.Type.TRANSPORT,"smtps",FixtureMailTransport::class.java.name,"test","1"))
    return session
  }
}
class FixtureMailStore(session:Session,url:URLName?):Store(session,url){
  override fun protocolConnect(host:String?,port:Int,user:String?,password:String?):Boolean{MailFixture.receivingConnections++;if(MailFixture.connectFails)throw AuthenticationFailedException("synthetic auth failure");return true}
  override fun getDefaultFolder():Folder=FixtureMailFolder(this,"root")
  override fun getFolder(name:String):Folder=FixtureMailFolder(this,name)
  override fun getFolder(url:URLName):Folder=getFolder(url.file)
}
class FixtureMailFolder(store:Store,private val folderName:String):Folder(store),UIDFolder{
  private var opened=false
  private val items get()=if(folderName=="Sent")MailFixture.sent else MailFixture.inbox
  override fun getName()=folderName
  override fun getFullName()=folderName
  override fun getParent():Folder?=null
  override fun exists()=true
  override fun list(pattern:String)=arrayOf(FixtureMailFolder(store,"INBOX"),FixtureMailFolder(store,"Sent"))
  override fun getSeparator()='/'
  override fun getType()=HOLDS_MESSAGES
  override fun create(type:Int)=false
  override fun hasNewMessages()=false
  override fun getFolder(name:String):Folder=FixtureMailFolder(store,name)
  override fun delete(recurse:Boolean)=false
  override fun renameTo(folder:Folder)=false
  override fun open(mode:Int){this.mode=mode;opened=true}
  override fun close(expunge:Boolean){check(!expunge);opened=false;MailFixture.closed++}
  override fun isOpen()=opened
  override fun getPermanentFlags()=Flags()
  override fun getMessageCount()=items.size
  override fun getMessage(number:Int)=items[number-1]
  override fun appendMessages(messages:Array<out Message>){if(MailFixture.copyFails)throw MessagingException("synthetic copy failure");MailFixture.appends+=messages.size;items.addAll(messages)}
  override fun expunge():Array<Message> = error("Read-only tests must not delete")
  override fun getUIDValidity()=42L
  override fun getUIDNext()=1001L+items.size
  override fun getUID(message:Message)=1001L+items.indexOf(message)
  override fun getMessageByUID(uid:Long):Message?=items.getOrNull((uid-1001).toInt())
  override fun getMessagesByUID(start:Long,end:Long)=items.filter{getUID(it) in start..end}.toTypedArray()
  override fun getMessagesByUID(uids:LongArray)=uids.toList().mapNotNull{getMessageByUID(it)}.toTypedArray()
}
class FixtureMailTransport(session:Session,url:URLName?):Transport(session,url){
  override fun protocolConnect(host:String?,port:Int,user:String?,password:String?):Boolean{MailFixture.sendingConnections++;if(MailFixture.connectFails)throw AuthenticationFailedException("synthetic auth failure");return true}
  override fun sendMessage(message:Message,addresses:Array<out Address>){MailFixture.sends++;check(message is MimeMessage&&message.getHeader("Message-ID")!=null);if(MailFixture.uncertain)throw MessagingException("synthetic connection lost after DATA");if(MailFixture.partial)throw SendFailedException("synthetic partial",null,arrayOf(addresses[0]),null,null)}
}
internal object SchoolMailChecks {
 fun reuse(report:(String)->Unit){
  val account=SchoolMailAccount("session@example.invalid","SensitiveAppCode")
  MailFixture.connectFails=false;MailFixture.uncertain=false;MailFixture.receivingConnections=0;MailFixture.sendingConnections=0
  SchoolMailConnections.invalidate()
  try{
    val store=SchoolMailConnections.receiving(account,MailFixture.session()){it}
    repeat(3){check(SchoolMailConnections.receiving(account,MailFixture.session()){it}===store)}
    check(MailFixture.receivingConnections==1)
    val transport=SchoolMailConnections.sending(account,MailFixture.session()){it}
    check(SchoolMailConnections.sending(account,MailFixture.session()){it}===transport&&MailFixture.sendingConnections==1)
    store.close();val replaced=SchoolMailConnections.receiving(account,MailFixture.session()){it}
    check(replaced!==store&&MailFixture.receivingConnections==2)
    // Password comparison must be case sensitive, unlike normalized account identifiers.
    val changed=SchoolMailConnections.receiving(account.copy(secret="sensitiveappcode"),MailFixture.session()){it}
    check(changed!==replaced&&!replaced.isConnected&&!transport.isConnected)
    check(runCatching{SchoolMailConnections.receiving(account,MailFixture.session()){throw IllegalStateException("synthetic interrupted read")}}.isFailure)
    check(!changed.isConnected)
    val before=MailFixture.receivingConnections;MailFixture.connectFails=true
    check(runCatching{SchoolMailConnections.receiving(account,MailFixture.session()){}}.isFailure)
    check(MailFixture.receivingConnections==before+1) // no hidden authentication retry
    MailFixture.connectFails=false
    val recovered=SchoolMailConnections.receiving(account,MailFixture.session()){it};check(recovered.isConnected)
    SchoolMailConnections.invalidate()
    val reset=SchoolMailConnections.receiving(account,MailFixture.session()){it};check(reset!==recovered&&!recovered.isConnected)
    report("Mail session reuse: IMAP/SMTP authenticate once across operations; closed connections reconnect; password changes/invalidation/errors clear old state; failures never retry the command")
  }finally{MailFixture.connectFails=false;SchoolMailConnections.invalidate()}
 }
 fun run(context:Context,report:(String)->Unit){
  val session=MailFixture.session();val account=SchoolMailAccount("synthetic@example.invalid","synthetic-app-code")
  val client=SchoolMailClient(account,session)
  MailFixture.inbox.clear();MailFixture.sent.clear();MailFixture.sends=0;MailFixture.appends=0;MailFixture.connectFails=false;MailFixture.uncertain=false;MailFixture.partial=false;MailFixture.copyFails=false
  val multipart=MimeMultipart("mixed").apply{
    addBodyPart(MimeBodyPart().apply{setText("模拟正文 <script>只是文字</script>","UTF-8")})
    addBodyPart(MimeBodyPart().apply{fileName="模拟附件.txt";disposition=Part.ATTACHMENT;setText("附件不得被读取")})
  }
  val original=MimeMessage(session).apply{setFrom(InternetAddress("sender@example.invalid"));setRecipients(Message.RecipientType.TO,"synthetic@example.invalid");setSubject("模拟来信","UTF-8");setContent(multipart);saveChanges()}
  for(i in 1..65)MailFixture.inbox.add(MimeMessage(original).apply{setSubject("模拟来信$i","UTF-8")})
  client.verify();
  val first=client.list();check(first.items.size==30&&first.items.first().uid==1065L&&first.nextBefore==1036L)
  val second=client.list("INBOX",first.nextBefore,42);check(second.items.size==30&&second.items.none{m->first.items.any{it.uid==m.uid}})
  val last=client.list("INBOX",second.nextBefore,42);check(last.items.size==5&&last.nextBefore==null)
  check(runCatching{client.list("INBOX",1030,43)}.isFailure)
  val read=client.read("INBOX",1065,42);check(read.text.contains("模拟正文")&&read.attachments==listOf("模拟附件.txt")&&!read.truncated)
  check(!MailFixture.inbox.last().isSet(Flags.Flag.SEEN));check(client.folders().any{it.name=="Sent"})
  val html=MimeMessage(session).apply{setContent("<p>可见文字</p><script>bad()</script><img src='https://not-accessed.invalid/a'><form>hidden</form>","text/html; charset=UTF-8");saveChanges()}
  val htmlBody=SchoolMailMime.read(html);check(htmlBody.text.contains("可见文字")&&!htmlBody.text.contains("bad()")&&!htmlBody.text.contains("hidden"))
  val big=MimeMessage(session).apply{setText("A".repeat(600000),"UTF-8");saveChanges()};val clipped=SchoolMailMime.read(big);check(clipped.truncated&&clipped.text.length==512*1024)
  val alternative=MimeMessage(session).apply{setContent(MimeMultipart("alternative").apply{addBodyPart(MimeBodyPart().apply{setContent("<b>HTML alternate</b>","text/html")});addBodyPart(MimeBodyPart().apply{setText("plain preferred")})});saveChanges()};check(SchoolMailMime.read(alternative).text=="plain preferred")
  for(raw in listOf("x@example.invalid\r\nBcc:evil@example.invalid","invalid","a@bad"))check(runCatching{SchoolMailPolicy.addresses(raw)}.isFailure)
  check(runCatching{SchoolMailDraft("a@example.invalid","header\r\ninjection","body").validate()}.isFailure)
  check(SchoolMailPolicy.properties().getProperty("mail.imaps.ssl.checkserveridentity")=="true")
  check(SchoolMailPolicy.properties().getProperty("mail.smtps.ssl.checkserveridentity")=="true")
  val job=SchoolMailPolling.job(context,15);check(job.isPersisted&&job.intervalMillis==900000L&&job.networkType==android.app.job.JobInfo.NETWORK_TYPE_ANY)
  check(runCatching{SchoolMailPolling.job(context,1)}.isFailure)
  check(SchoolMailPolling.decide(-1,9999,first).count==0)
  val decision=SchoolMailPolling.decide(42,1063,first);check(decision.count==2&&decision.newest==1065L)
  check(SchoolMailPolling.decide(41,1064,first).count==0)
  check(SchoolMailPolling.decide(42,1065,first).count==0)
  check(SchoolMailPolling.decide(42,1065,first.copy(items=emptyList())).newest==1065L)
  report("Polling checkpoint: initial baseline has no old-mail alert, new UIDs counted once, folder epoch reset and empty response cannot rewind a valid cursor")
  report("Mail core: 30-item UID pagination, stale folder refusal, text/plain/HTML/alternative MIME, attachment metadata only, 512KB clipping, no seen mutation, header injection refusal and verified TLS policy passed")
  val dbName="school_mail_outbox_test.db";context.deleteDatabase(dbName)
  SchoolMailOutbox(context,dbName).use{outbox->
    val id=UUID.randomUUID().toString();val draft=SchoolMailDraft("receiver@example.invalid","模拟主题","模拟正文")
    val result=client.send(id,draft,outbox);check(result.state=="accepted"&&MailFixture.sends==1&&MailFixture.appends==1)
    check(runCatching{client.send(id,draft,outbox)}.isFailure&&MailFixture.sends==1)
    MailFixture.connectFails=true;val failed=client.send(UUID.randomUUID().toString(),draft,outbox);check(failed.state=="failed");MailFixture.connectFails=false
    MailFixture.uncertain=true;val unknownId=UUID.randomUUID().toString();val unknown=client.send(unknownId,draft,outbox);check(unknown.state=="unknown"&&MailFixture.sends==2);MailFixture.uncertain=false
    check(runCatching{client.send(unknownId,draft,outbox)}.isFailure&&MailFixture.sends==2)
    MailFixture.partial=true;val partial=client.send(UUID.randomUUID().toString(),draft,outbox);check(partial.state=="partial"&&MailFixture.sends==3);MailFixture.partial=false
    MailFixture.copyFails=true;val noCopy=client.send(UUID.randomUUID().toString(),draft,outbox);check(noCopy.state=="accepted"&&MailFixture.sends==4&&noCopy.detail.contains("副本未能确认"));MailFixture.copyFails=false
    outbox.begin(UUID.randomUUID().toString(),account.address,"<synthetic-interrupted@example.invalid>");outbox.recoverInterrupted();check(outbox.latest(account.address).none{it.state=="sending"})
    check(outbox.latest("other@example.invalid").isEmpty())
  }
  SchoolMailOutbox(context,dbName).use{check(it.latest(account.address).count{it.state=="unknown"}==2)};context.deleteDatabase(dbName)
  val vault=LibraryCredentialStore(context,"school_mail_credentials_test");try{check(vault.save(LibraryCredentialStore.Credentials(account.address,account.secret,"email")));check(vault.load()?.password==account.secret);val bytes=java.io.File(context.noBackupFilesDir,"school_mail_credentials_test.enc").readText();check(!bytes.contains(account.secret)&&!bytes.contains(account.address))}finally{vault.clear()}
  report("Mail send: fake transport acceptance vs auth failure vs unknown result, duplicate draft refusal, sent-copy save, persistent interruption recovery, account isolation and encrypted app-code storage passed; ZERO external messages sent")
 }
}
