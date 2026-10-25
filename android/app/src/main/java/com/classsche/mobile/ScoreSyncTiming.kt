package com.classsche.mobile

/** Time until the next background alarm; startup attempts and failures reset the interval too. */
internal object ScoreSyncTiming {
  fun remainingDelayMillis(lastAttemptMillis: Long, nowMillis: Long, intervalMillis: Long): Long {
    if (lastAttemptMillis <= 0L) return 0L
    val elapsed = (nowMillis - lastAttemptMillis).coerceAtLeast(0L)
    return (intervalMillis - elapsed).coerceAtLeast(0L)
  }
}
