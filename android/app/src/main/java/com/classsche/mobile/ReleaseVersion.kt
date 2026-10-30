package com.classsche.mobile

/** Preserve preview suffixes and rank the eventual stable build above its previews. */
internal object ReleaseVersion {
  fun extract(raw: String?): String? = Regex("""(?i)v?(\d+(?:\.\d+){1,3}(?:-(?:alpha|beta|rc)\.\d+)?)""")
    .find(raw.orEmpty())?.groupValues?.getOrNull(1)?.lowercase(java.util.Locale.ROOT)
  fun compare(left: String, right: String): Int {
    val a=extract(left).orEmpty().split('-',limit=2);val b=extract(right).orEmpty().split('-',limit=2)
    val ac=a[0].split('.').map{it.toLongOrNull()?:0};val bc=b[0].split('.').map{it.toLongOrNull()?:0}
    for(i in 0 until maxOf(ac.size,bc.size)){val result=ac.getOrElse(i){0}.compareTo(bc.getOrElse(i){0});if(result!=0)return result}
    if(a.size==1||b.size==1)return if(a.size==b.size)0 else if(a.size==1)1 else -1
    val ap=a[1].split('.');val bp=b[1].split('.');val ranks=listOf("alpha","beta","rc")
    val channel=ranks.indexOf(ap[0]).compareTo(ranks.indexOf(bp[0]));if(channel!=0)return channel
    return (ap.getOrNull(1)?.toLongOrNull()?:0).compareTo(bp.getOrNull(1)?.toLongOrNull()?:0)
  }
}
