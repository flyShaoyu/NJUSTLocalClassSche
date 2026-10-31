package com.classsche.mobile

internal object BrowserUserAgent {
  fun desktop(value:String)=value.replace(Regex("\\(Linux; Android[^)]*\\)"),"(Windows NT 10.0; Win64; x64)")
    .replace("; wv","").replace(" Version/4.0","").replace(" Mobile","")
}
