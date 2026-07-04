package com.classsche.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object ScoreSyncSettings {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_MODE = "score_sync_mode"
  private const val PREF_INTERVAL_HOURS = "score_sync_interval_hours"
  private val ALLOWED_INTERVAL_HOURS = intArrayOf(1, 2, 5, 12, 24)

  enum class Mode(val storageValue: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    WIFI_ONLY("wifi_only");

    companion object {
      fun fromStorageValue(value: String?): Mode {
        return values().firstOrNull { it.storageValue == value } ?: ENABLED
      }
    }
  }

  data class Snapshot(
    val mode: Mode,
    val intervalHours: Int
  ) {
    val intervalMillis: Long
      get() = intervalHours * 60L * 60L * 1000L
  }

  fun read(context: Context): Snapshot {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val mode = Mode.fromStorageValue(prefs.getString(PREF_MODE, Mode.ENABLED.storageValue))
    val savedIntervalHours = prefs.getInt(PREF_INTERVAL_HOURS, 2)
    val intervalHours = savedIntervalHours.takeIf { it in ALLOWED_INTERVAL_HOURS } ?: 2
    return Snapshot(mode = mode, intervalHours = intervalHours)
  }

  fun intervalOptionsHours(): IntArray = ALLOWED_INTERVAL_HOURS.copyOf()

  fun saveMode(context: Context, mode: Mode) {
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(PREF_MODE, mode.storageValue)
      .apply()
  }

  fun saveIntervalHours(context: Context, intervalHours: Int) {
    val normalized = intervalHours.takeIf { it in ALLOWED_INTERVAL_HOURS } ?: 2
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putInt(PREF_INTERVAL_HOURS, normalized)
      .apply()
  }

  fun shouldRunNow(context: Context): Boolean = skipReason(context) == null

  fun skipReason(context: Context): String? {
    return when (read(context).mode) {
      Mode.DISABLED -> "已在设置中关闭后台成绩拉取"
      Mode.WIFI_ONLY -> if (isOnWifi(context)) null else "当前不是 Wi-Fi 网络"
      Mode.ENABLED -> null
    }
  }

  fun isOnWifi(context: Context): Boolean {
    val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
      ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }
}
