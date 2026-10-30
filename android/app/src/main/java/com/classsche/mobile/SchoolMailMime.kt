package com.classsche.mobile

import jakarta.mail.Part
import jakarta.mail.Multipart
import jakarta.mail.internet.MimeUtility
import org.jsoup.Jsoup
import java.io.InputStream

/** No WebView or remote images: render mail as text, leave attachment bytes on server. */
internal object SchoolMailMime {
  private const val MAX_BYTES=512*1024
  data class Body(val text:String,val attachments:List<String>,val truncated:Boolean)
  fun read(part:Part):Body {
    val files=mutableListOf<String>();var truncated=false;var remaining=MAX_BYTES;var visited=0
    fun bounded(input:InputStream):ByteArray=input.use{
      val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
      while(remaining>0){val n=it.read(buffer,0,minOf(buffer.size,remaining));if(n<0)break;out.write(buffer,0,n);remaining-=n}
      if(remaining==0)truncated=true
      out.toByteArray()
    }
    fun walk(p:Part,depth:Int):String {
      if(++visited>100||depth>12){truncated=true;return ""}
      val file=runCatching{p.fileName?.let{MimeUtility.decodeText(it)}}.getOrNull()
      if(p.disposition.equals(Part.ATTACHMENT,true)||!file.isNullOrBlank()){
        files.add((file?:"未命名附件").take(200));return ""
      }
      if(p.isMimeType("text/plain")||p.isMimeType("text/html")){
        if(remaining<=0){truncated=true;return ""}
        val bytes=bounded(p.inputStream)
        val encoding=runCatching{jakarta.mail.internet.ContentType(p.contentType).getParameter("charset")}.getOrNull()
        val text=runCatching{String(bytes,charset(encoding?:"UTF-8"))}.getOrElse{String(bytes,Charsets.UTF_8)}
        if(p.isMimeType("text/plain"))return text
        val doc=Jsoup.parse(text);doc.select("script,style,iframe,object,embed,form").remove();doc.select("br").append("\n");doc.select("p,div,tr,li,h1,h2,h3").prepend("\n")
        return doc.body().wholeText().replace(Regex("\n{3,}"),"\n\n").trim()
      }
      if(p.isMimeType("multipart/*")){
        val multi=p.content as? Multipart?:return ""
        val count=minOf(multi.count,100);if(multi.count>100)truncated=true;if(count==0)return ""
        if(p.isMimeType("multipart/alternative")){
          // Prefer plain text, don't download both full representations.
          val plain=(0 until count).firstOrNull{multi.getBodyPart(it).isMimeType("text/plain")}
          return walk(multi.getBodyPart(plain?:((0 until count).firstOrNull{multi.getBodyPart(it).isMimeType("text/html")}?:0)),depth+1)
        }
        return (0 until count).map{walk(multi.getBodyPart(it),depth+1)}.filter{it.isNotBlank()}.joinToString("\n\n")
      }
      if(p.isMimeType("message/rfc822")){files.add("转发邮件.eml（首版暂不展开）");return ""}
      return ""
    }
    return Body(walk(part,0),files,truncated)
  }
}
