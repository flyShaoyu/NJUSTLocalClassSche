package com.classsche.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import com.classsche.mobile.databinding.ActivityNetworkLoginBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dedicated process, Wi-Fi route and WebView data. No JavaScript bridge on school pages. */
class NetworkLoginActivity:AppCompatActivity(){
  companion object {
    const val PORTAL_URL="http://m.njust.edu.cn/portal/index.html"
    private var storageConfigured=false
  }
  private lateinit var binding:ActivityNetworkLoginBinding
  private val connectivity by lazy{getSystemService(ConnectivityManager::class.java)}
  private val handler=Handler(Looper.getMainLooper())
  private var callback:ConnectivityManager.NetworkCallback?=null
  private var session:WifiPortalSession<Network>?=null
  private var browser:WebView?=null
  private var pageTimeout:Runnable?=null
  private var storageReady=false

  override fun onCreate(state:Bundle?){
    super.onCreate(state)
    binding=ActivityNetworkLoginBinding.inflate(layoutInflater);setContentView(binding.root)
    binding.toolbar.title="网络登录";binding.toolbar.subtitle="m.njust.edu.cn"
    SecondaryPageToolbar.bind(binding.root,binding.toolbar,domainSubtitle=true)
    binding.toolbar.setNavigationOnClickListener{onBackPressed()}
    binding.wifiSettings.setOnClickListener{startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}
    binding.retryPortal.setOnClickListener{
      if(session?.isReady==true){if(browser==null)openPortal()else browser?.loadUrl(PORTAL_URL)}else requestWifi()
    }
    storageReady=runCatching{
      if(!storageConfigured){
        if(Build.VERSION.SDK_INT>=28)WebView.setDataDirectorySuffix("wifi_portal")
        else {
          check(WebViewFeature.isStartupFeatureSupported(this,WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)){"请先更新 Android System WebView 后再使用网络登录"}
          ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(this,"wifi_portal"))
        }
        storageConfigured=true
      }
      true
    }.getOrElse{showStatus(it.message?:"网页组件初始化失败，请更新系统 WebView 后重试");false}
    binding.retryPortal.isEnabled=storageReady
    if(storageReady)requestWifi()
  }

  private fun requestWifi(){
    if(!storageReady)return
    releaseSession()
    showStatus("正在查找 Wi-Fi…请先连接校园 Wi-Fi，尚未认证也可以使用。")
    binding.retryPortal.text="重新连接"
    val connection=WifiPortalSession<Network>(
      bind={runCatching{connectivity.bindProcessToNetwork(it)}.getOrDefault(false)},
      open={openPortal()},stop={destroyBrowser()},changed={value->
        if(!isFinishing&&!isDestroyed)when(value){
          WifiPortalSession.State.READY->{binding.retryPortal.text="重新打开";showStatus("已连接 Wi-Fi，正在打开认证网页…")}
          WifiPortalSession.State.NO_WIFI->showStatus("未找到可用的 Wi-Fi。请连接校园 Wi-Fi 后点“重新连接”，此页不会使用移动数据。")
          WifiPortalSession.State.LOST->showStatus("Wi-Fi 已断开或被系统限制，网页已停止。重新连接 Wi-Fi 后再试。")
          WifiPortalSession.State.BIND_FAILED->showStatus("无法使用当前 Wi-Fi，网页未加载。请检查 Wi-Fi 或 VPN/系统网络限制后重试。")
          else->Unit
        }
      })
    session=connection
    val listener=object:ConnectivityManager.NetworkCallback(){
      override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){connection.available(network,caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))}
      override fun onLost(network:Network){connection.lost(network)}
      override fun onBlockedStatusChanged(network:Network,blocked:Boolean){if(blocked)connection.lost(network)}
      override fun onUnavailable(){if(callback===this)callback=null;connection.unavailable()}
    }
    callback=listener
    try{
      // Captive Wi-Fi need not have INTERNET or VALIDATED capability yet.
      val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
      connectivity.requestNetwork(request,listener,handler,15000)
    }catch(error:Exception){
      callback=null;connection.close()
      showStatus(if(error is SecurityException)"系统未允许使用 Wi-Fi 网络，请检查网络权限或系统限制" else "请求 Wi-Fi 失败，请稍后重新连接")
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun openPortal(){
    val connection=session?:return
    if(!connection.isReady||isFinishing||isDestroyed)return
    destroyBrowser()
    runCatching{
      val view=WebView(this);browser=view
      with(view.settings){javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false;cacheMode=WebSettings.LOAD_NO_CACHE;setSupportZoom(true);builtInZoomControls=true;displayZoomControls=false}
      view.setBackgroundColor(Color.WHITE)
      view.webChromeClient=object:WebChromeClient(){
        override fun onJsAlert(view:WebView,url:String,message:String,result:JsResult):Boolean{
          MaterialAlertDialogBuilder(this@NetworkLoginActivity).setMessage(message).setPositiveButton("确定"){_,_->result.confirm()}.setOnCancelListener{result.cancel()}.show();return true
        }
        override fun onJsConfirm(view:WebView,url:String,message:String,result:JsResult):Boolean{
          MaterialAlertDialogBuilder(this@NetworkLoginActivity).setMessage(message).setPositiveButton("确定"){_,_->result.confirm()}.setNegativeButton("取消"){_,_->result.cancel()}.setOnCancelListener{result.cancel()}.show();return true
        }
      }
      view.webViewClient=object:WebViewClient(){
        private var failed=false
        private fun active(view:WebView)=browser===view&&session===connection&&connection.isReady&&!isFinishing&&!isDestroyed
        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
          val allowed=active(view)&&CampusBrowserActivity.allowedUrl(request.url.toString())
          if(!allowed&&request.isForMainFrame)showStatus("网页跳转地址不受支持，请返回认证首页重试")
          return !allowed
        }
        override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?){
          if(!active(view))return
          failed=false;cancelPageTimeout();binding.toolbar.subtitle=android.net.Uri.parse(url).host.orEmpty()
          showStatus("正在通过 Wi-Fi 加载网页…")
          pageTimeout=Runnable{if(active(view)){failed=true;view.stopLoading();showStatus("网页加载超时，请确认已连接校园 Wi-Fi，再点“重新打开”")}}.also{handler.postDelayed(it,30000)}
        }
        override fun onPageFinished(view:WebView,url:String){if(active(view)){cancelPageTimeout();if(!failed)showStatus("仅使用 Wi-Fi。请在下方学校网页中完成网络登录。")}}
        override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
          if(!request.isForMainFrame||!active(view))return
          failed=true;cancelPageTimeout()
          val label=when(error.errorCode){ERROR_TIMEOUT->"网页连接超时";ERROR_HOST_LOOKUP->"无法解析网站域名";ERROR_FAILED_SSL_HANDSHAKE->"网站安全连接失败";ERROR_REDIRECT_LOOP->"目标网站跳转错误";else->"网页连接失败"}
          showStatus("$label（代码 ${error.errorCode}），请确认已连接校园 Wi-Fi 后重试")
        }
        override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame&&active(view)){failed=true;cancelPageTimeout();showStatus("网站返回错误：HTTP ${response.statusCode}，请稍后重试")}}
      }
      binding.portalContainer.addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
      view.loadUrl(PORTAL_URL)
    }.onFailure{destroyBrowser();showStatus("网页组件启动失败，请更新系统 WebView 后重新打开")}
  }

  private fun showStatus(value:String){binding.networkStatus.text=value}
  private fun cancelPageTimeout(){pageTimeout?.let(handler::removeCallbacks);pageTimeout=null}
  private fun destroyBrowser(){
    cancelPageTimeout()
    val view=browser;browser=null
    if(view!=null){view.stopLoading();binding.portalContainer.removeView(view);view.destroy()}
  }
  private fun releaseSession(){callback?.let{runCatching{connectivity.unregisterNetworkCallback(it)}};callback=null;session?.close();session=null}
  @Deprecated("Deprecated in Java") override fun onBackPressed(){if(browser?.canGoBack()==true)browser?.goBack()else finish()}
  override fun onDestroy(){releaseSession();destroyBrowser();super.onDestroy()}
}
