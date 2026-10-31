package com.classsche.mobile

import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authenticated protocol connections, not cookies. Never retries an operation (especially SMTP DATA). */
internal object SchoolMailConnections {
  private val lock=Any()
  private val generation=AtomicInteger()
  private val cleanup=Executors.newSingleThreadScheduledExecutor{run->Thread(run,"mail-idle-close").apply{isDaemon=true}}
  private var expiry:ScheduledFuture<*>?=null
  private var owner=""
  private var ownerGeneration=0
  private var usage=0L
  private var receiving:Store?=null
  private var sending:Transport?=null
  private fun key(account:SchoolMailAccount)=SchoolMailPolicy.accountKey(account.address)+":"+java.security.MessageDigest.getInstance("SHA-256").digest(account.secret.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
  private fun closeLocked(){
    expiry?.cancel(false);expiry=null
    val oldStore=receiving;val oldTransport=sending;receiving=null;sending=null;owner=""
    runCatching{oldStore?.close()};runCatching{oldTransport?.close()}
  }
  private fun prepare(account:SchoolMailAccount){
    if(owner!=key(account)||ownerGeneration!=generation.get())closeLocked()
    owner=key(account);ownerGeneration=generation.get();usage++;expiry?.cancel(false)
  }
  private fun expire(){
    val expectedOwner=owner;val expectedGeneration=ownerGeneration
    val expectedUsage=usage
    expiry=cleanup.schedule({synchronized(lock){if(owner==expectedOwner&&ownerGeneration==expectedGeneration&&usage==expectedUsage)closeLocked()}},2,TimeUnit.MINUTES)
  }
  fun invalidate(){val changed=generation.incrementAndGet();cleanup.execute{synchronized(lock){if(ownerGeneration<changed)closeLocked()}}}
  fun accountSaved(account:SchoolMailAccount){cleanup.execute{synchronized(lock){if(owner.isNotEmpty()&&owner!=key(account))closeLocked()}}}
  fun <T> receiving(account:SchoolMailAccount,session:Session,operation:(Store)->T):T=synchronized(lock){
    prepare(account)
    try{
      var store=receiving
      if(store==null||!store.isConnected){runCatching{store?.close()};store=session.getStore("imaps");receiving=store;store.connect(SchoolMailPolicy.IMAP_HOST,993,account.address,account.secret)}
      operation(requireNotNull(store))
    }catch(error:Throwable){closeLocked();throw error}finally{if(owner.isNotEmpty())expire()}
  }
  fun <T> sending(account:SchoolMailAccount,session:Session,operation:(Transport)->T):T=synchronized(lock){
    prepare(account)
    try{
      var transport=sending
      if(transport==null||!transport.isConnected){runCatching{transport?.close()};transport=session.getTransport("smtps");sending=transport;transport.connect(SchoolMailPolicy.SMTP_HOST,465,account.address,account.secret)}
      operation(requireNotNull(transport))
    }catch(error:Throwable){closeLocked();throw error}finally{if(owner.isNotEmpty())expire()}
  }
}
