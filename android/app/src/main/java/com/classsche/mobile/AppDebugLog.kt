package com.classsche.mobile

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AppDebugLog {
  private const val LOG_FILE_NAME = "app-debug.log"
  private const val MAX_LINES = 800
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

  @Synchronized
  fun append(context: Context, scope: String, status: String, message: String) {
    val sanitizedMessage = message
      .replace("\r", " ")
      .replace("\n", " | ")
      .trim()
      .ifBlank { "-" }
    val line = "${LocalDateTime.now().format(formatter)} [$scope] [$status] $sanitizedMessage"
    val file = File(context.filesDir, LOG_FILE_NAME)
    val existingLines = runCatching {
      if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()
    }.getOrDefault(emptyList())
    val updatedLines = (existingLines.takeLast(MAX_LINES - 1) + line)
    file.writeText(updatedLines.joinToString("\n"), Charsets.UTF_8)
  }

  @Synchronized
  fun read(context: Context): String {
    val file = File(context.filesDir, LOG_FILE_NAME)
    if (!file.exists()) {
      return ""
    }
    return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
  }

  @Synchronized
  fun clear(context: Context) {
    val file = File(context.filesDir, LOG_FILE_NAME)
    if (file.exists()) {
      file.writeText("", Charsets.UTF_8)
    }
  }
}
