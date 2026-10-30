package com.classsche.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Only delivery metadata is persisted. Email bodies and secrets never enter this journal. */
internal class SchoolMailOutbox(context:Context,name:String="school_mail_outbox.db"):SQLiteOpenHelper(context,name,null,1){
  data class Entry(val id:String,val account:String,val messageId:String,val state:String,val updated:Long,val detail:String)
  override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE sends(id TEXT PRIMARY KEY,account TEXT NOT NULL,message_id TEXT NOT NULL,state TEXT NOT NULL,updated INTEGER NOT NULL,detail TEXT NOT NULL)")}
  override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=Unit
  @Synchronized fun begin(id:String,account:String,messageId:String){
    val values=android.content.ContentValues().apply{put("id",id);put("account",SchoolMailPolicy.accountKey(account));put("message_id",messageId);put("state","sending");put("updated",System.currentTimeMillis());put("detail","正在发送；未获得回执前不能认定成功")}
    check(writableDatabase.insertWithOnConflict("sends",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L){"这封邮件已提交过，请先核对发送状态，勿重复发送"}
  }
  @Synchronized fun update(id:String,state:String,detail:String){
    require(state in listOf("accepted","failed","unknown","partial"))
    val values=android.content.ContentValues().apply{put("state",state);put("updated",System.currentTimeMillis());put("detail",detail)}
    check(writableDatabase.update("sends",values,"id=?",arrayOf(id))==1){"发送记录更新失败"}
  }
  @Synchronized fun find(id:String):Entry?=readableDatabase.query("sends",arrayOf("id","account","message_id","state","updated","detail"),"id=?",arrayOf(id),null,null,null,"1").use{c->if(c.moveToFirst())Entry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5))else null}
  @Synchronized fun latest(account:String):List<Entry> = readableDatabase.query("sends",arrayOf("id","account","message_id","state","updated","detail"),"account=?",arrayOf(SchoolMailPolicy.accountKey(account)),null,null,"updated DESC","20").use{c->buildList{while(c.moveToNext())add(Entry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5)))}}
  // Only call at application process start, never while the same process has an active sender.
  @Synchronized fun recoverInterrupted(){val values=android.content.ContentValues().apply{put("state","unknown");put("detail","应用上次中断，发送结果未知；先查已发送或向收件人核对，勿直接重发");put("updated",System.currentTimeMillis())};writableDatabase.update("sends",values,"state=?",arrayOf("sending"))}
}
