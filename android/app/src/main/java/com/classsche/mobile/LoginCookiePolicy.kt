package com.classsche.mobile

import java.net.URI

/** Limit server cookie domains to their origin or the university parent domain. */
internal object LoginCookiePolicy {
  fun acceptsScope(url: String, header: String): Boolean {
    val host = URI(url).host?.lowercase() ?: return false
    val domains = header.split(';').drop(1).filter { it.substringBefore('=').trim().equals("Domain", true) }
    return domains.all { attribute ->
      val domain = attribute.substringAfter('=', "").trim().trim('"').trimStart('.').lowercase()
      domain == host || domain == "njust.edu.cn" && host.endsWith(".njust.edu.cn")
    }
  }

  fun filterHeaders(url: String, headers: Map<String, List<String>>): Map<String, List<String>> = headers.mapValues { (name, values) ->
    if (name.equals("Set-Cookie", true)) values.filter { acceptsScope(url, it) } else values
  }
}
