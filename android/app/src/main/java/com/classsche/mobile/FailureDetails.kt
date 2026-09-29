package com.classsche.mobile

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object FailureDetails {
  fun describe(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val causes = generateSequence(error) { it.cause }.take(6).toList()
    if (detail.startsWith("目标网站跳转错误") || detail.startsWith("网站返回错误") || detail.startsWith("页面解析错误")) return detail
    return when {
      causes.any { it is SocketTimeoutException } -> "网页连接超时：$detail，请检查网络后重试"
      causes.any { it is UnknownHostException } -> "无法解析目标网站域名：$detail，请检查网络或站点地址"
      causes.any { it is SSLException } -> "网站安全连接失败：$detail"
      Regex("HTTP 3\\d\\d").containsMatchIn(detail) -> "目标网站跳转错误：$detail"
      detail.contains("跳转错误") || detail.contains("重定向") -> "目标网站跳转错误：$detail"
      detail.contains("HTTP ", ignoreCase = true) -> "网站返回错误：$detail"
      detail.contains("表格") || detail.contains("解析") || detail.contains("未找到") -> "页面解析错误：$detail"
      else -> detail
    }
  }
}
