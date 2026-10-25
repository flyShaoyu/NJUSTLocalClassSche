package com.classsche.mobile

/** Keep one instance for the process: reopening an Activity must not repeat startup sync. */
internal class ScoreSyncTriggerPolicy {
  private var appOpenHandled = false

  @Synchronized
  fun skipReason(
    isAppOpen: Boolean,
    syncInProgress: Boolean,
    settingsSkipReason: String?,
    authenticationFailure: String?,
    remainingDelayMillis: Long
  ): String? {
    if (isAppOpen) {
      if (appOpenHandled) return "本次应用进程已处理启动同步，返回前台不重复同步"
      // Consume even a skipped/failed startup attempt; returning from settings is not a new launch.
      appOpenHandled = true
    }
    if (syncInProgress) return "已有同步在进行中"
    if (settingsSkipReason != null) return settingsSkipReason
    if (authenticationFailure != null) {
      return "自动认证已暂停：$authenticationFailure；请在登录页手动完成一次登录"
    }
    if (!isAppOpen && remainingDelayMillis > 0L) {
      return "尚未到设置的同步间隔，约 ${(remainingDelayMillis + 59_999L) / 60_000L} 分钟后可再次自动同步"
    }
    return null
  }
}
