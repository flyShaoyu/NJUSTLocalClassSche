package com.classsche.mobile

internal object SchoolMailIntervalPolicy {
  val values=listOf(0,15,60,600,1440,2880,7200)
  /** Removed choices move to the next longer interval; disabled remains disabled. */
  fun normalize(minutes:Int):Int=if(minutes<=0)0 else values.firstOrNull{it>=minutes}?:0
}
