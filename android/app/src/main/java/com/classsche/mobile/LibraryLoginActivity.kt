package com.classsche.mobile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import android.webkit.*
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.classsche.mobile.databinding.ActivityLibraryLoginBinding
import org.json.JSONObject
import org.json.JSONTokener

/** Native credentials saved encrypted only after authenticated submission; the official page still owns CSRF and password encoding. */
class LibraryLoginActivity : AppCompatActivity() {
  private lateinit var binding: ActivityLibraryLoginBinding
  private val handler=Handler(Looper.getMainLooper())
  private val loginUrl=CampusDataClient.LIBRARY+"reader/login.php"
  private var generation=0
  private var pending=false
  private var ready=false
  private var lastFailure=""
  private var captchaBitmap:Bitmap?=null
  private lateinit var credentialStore:LibraryCredentialStore
  private var submittedCredentials:LibraryCredentialStore.Credentials?=null

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState:Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    binding=ActivityLibraryLoginBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.toolbar.title="图书馆登录"
    SecondaryPageToolbar.bind(binding.root,binding.toolbar)
    binding.toolbar.setNavigationOnClickListener{finish()}
    binding.libraryLoginType.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("条码号（学号）","证件号","Email"))
    credentialStore=LibraryCredentialStore(this)
    restoreCredentials()
    with(binding.libraryLoginWebView.settings){
      javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false
      cacheMode=WebSettings.LOAD_NO_CACHE;saveFormData=false
    }
    binding.libraryLoginWebView.webViewClient=object:WebViewClient(){
      override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean=!trusted(r.url.toString())
      override fun onPageStarted(v:WebView,url:String,icon:Bitmap?){generation++;ready=false;binding.librarySubmit.isEnabled=false}
      override fun onPageFinished(v:WebView,url:String){if(trusted(url))probe(generation,0)}
      override fun onReceivedError(v:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame)fail("图书馆登录页面连接失败，请重新获取验证码")}
      override fun onReceivedHttpError(v:WebView,r:WebResourceRequest,response:WebResourceResponse){if(r.isForMainFrame)fail("图书馆登录页面返回HTTP ${response.statusCode}")}
    }
    binding.libraryLoginWebView.webChromeClient=object:WebChromeClient(){
      override fun onJsAlert(v:WebView,url:String,message:String,result:JsResult):Boolean{
        if(!trusted(url))return false
        lastFailure=when{message.contains("验证码")->"验证码不正确，请换一张后重试";message.contains("密码")||message.contains("用户名")->"账号、图书馆密码或登录类型不正确";else->"图书馆未接受登录，请检查输入"}
        submittedCredentials=null;pending=false;binding.libraryLoginStatus.text=lastFailure;result.confirm()
        handler.post{reload(false)};return true
      }
    }
    binding.libraryRefreshCaptcha.setOnClickListener{reload(true)}
    binding.librarySubmit.setOnClickListener{submit()}
    reload(true,true)
  }
  private fun restoreCredentials(){
    credentialStore.load()?.let { saved ->
      binding.libraryUsername.setText(saved.username);binding.libraryPassword.setText(saved.password)
      binding.libraryLoginType.setSelection(listOf("bar_no","cert_no","email").indexOf(saved.type).coerceAtLeast(0))
    }
  }
  private fun completeLogin(){
    val submitted=submittedCredentials;submittedCredentials=null
    if(submitted!=null&&!credentialStore.save(submitted))android.widget.Toast.makeText(this,"登录成功，但账密加密保存失败；下次需重新输入",android.widget.Toast.LENGTH_LONG).show()
    CookieManager.getInstance().flush();binding.libraryPassword.text?.clear();binding.libraryCaptcha.text?.clear();setResult(RESULT_OK);finish()
  }
  private fun trusted(url:String)=url.startsWith(CampusDataClient.LIBRARY)&&!url.contains("@")
  private fun fail(message:String){submittedCredentials=null;pending=false;binding.libraryLoginStatus.text=message;binding.librarySubmit.isEnabled=false;binding.libraryRefreshCaptcha.isEnabled=true}
  private fun reload(clearFailure:Boolean,reuseExisting:Boolean=false){
    if(isFinishing||isDestroyed)return
    if(clearFailure)lastFailure=""
    submittedCredentials=null;pending=false;ready=false;binding.librarySubmit.isEnabled=false;binding.libraryCaptcha.text?.clear()
    binding.libraryCaptchaImage.setImageDrawable(null);captchaBitmap?.recycle();captchaBitmap=null
    binding.libraryLoginStatus.text=lastFailure.ifBlank{"正在准备验证码…"}
    binding.libraryLoginWebView.loadUrl(if(reuseExisting)CampusDataClient.LIBRARY+"reader/redr_info.php" else loginUrl)
  }
  private fun probe(version:Int,attempt:Int){
    if(isFinishing||isDestroyed||version!=generation)return
    val script="""JSON.stringify((()=>{
      if(!location.href.startsWith(${JSONObject.quote(CampusDataClient.LIBRARY)}))return {error:true};
      const authenticated=[...document.querySelectorAll('a[href]')].some(a=>/logout/i.test(a.getAttribute('href')||'')&&/注销|退出/.test(a.textContent.replace(/\s/g,'')));
      if(authenticated)return {authenticated:true};
      const form=document.querySelector('form[action*="redr_verify.php"]');if(!form)return {missing:true};
      const img=document.querySelector('img[src*="captcha.php"]');if(!img||!img.complete||!img.naturalWidth)return {waiting:true};
      try{const canvas=document.createElement('canvas');canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;if(canvas.width>600||canvas.height>300)return {error:true};canvas.getContext('2d').drawImage(img,0,0);return {image:canvas.toDataURL('image/png')}}catch(_){return {error:true}};
    })())""".trimIndent()
    binding.libraryLoginWebView.evaluateJavascript(script){raw->
      if(isFinishing||isDestroyed||version!=generation)return@evaluateJavascript
      val data=runCatching{JSONObject(JSONTokener(raw).nextValue() as String)}.getOrNull()
      if(data?.optBoolean("authenticated")==true){
        completeLogin();return@evaluateJavascript
      }
      if(data?.has("image")==true){
        val encoded=data.optString("image").substringAfter("base64,","")
        val image=runCatching{if(encoded.length>250000)error("Image too large");val bytes=Base64.decode(encoded,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()
        if(image==null){fail("验证码读取失败，请点击换一张");return@evaluateJavascript}
        captchaBitmap?.recycle();captchaBitmap=image;binding.libraryCaptchaImage.setImageBitmap(image)
        ready=true;pending=false;binding.librarySubmit.isEnabled=true;binding.libraryRefreshCaptcha.isEnabled=true
        binding.libraryLoginStatus.text=lastFailure.ifBlank{"请手动输入图形验证码"};return@evaluateJavascript
      }
      if(data?.optBoolean("waiting")==true&&attempt<40){handler.postDelayed({probe(version,attempt+1)},250);return@evaluateJavascript}
      if(pending){lastFailure="登录未成功，请检查图书馆账号、密码及验证码";pending=false;reload(false)}
      else fail("登录表单或验证码未能准备，请点击换一张")
    }
  }
  private fun submit(){
    if(!ready||pending)return
    val username=binding.libraryUsername.text?.toString()?.trim().orEmpty()
    val password=binding.libraryPassword.text?.toString().orEmpty()
    val captcha=binding.libraryCaptcha.text?.toString()?.trim().orEmpty()
    if(username.isEmpty()||password.isEmpty()||captcha.isEmpty()){binding.libraryLoginStatus.text="请填写账号、图书馆密码和验证码";return}
    val type=listOf("bar_no","cert_no","email")[binding.libraryLoginType.selectedItemPosition.coerceIn(0,2)]
    val script="""(()=>{
      if(!location.href.startsWith(${JSONObject.quote(CampusDataClient.LIBRARY)}))return false;
      const f=document.querySelector('form[action*="redr_verify.php"]');if(!f||f.method.toLowerCase()!=='post')return false;
      if(new URL(f.action,location.href).href.split('?')[0]!==${JSONObject.quote(CampusDataClient.LIBRARY+"reader/redr_verify.php")})return false;
      const u=f.querySelector('[name=number]'),p=f.querySelector('[name=passwd]'),c=f.querySelector('[name=captcha]'),t=f.querySelector('[name=select][value="'+${JSONObject.quote(type)}+'"]');if(!u||!p||!c||!t)return false;
      u.value=${JSONObject.quote(username)};p.value=${JSONObject.quote(password)};c.value=${JSONObject.quote(captcha)};t.checked=true;
      if(f.requestSubmit)f.requestSubmit();else f.querySelector('input[type=submit],button[type=submit]').click();return true;
    })()""".trimIndent()
    submittedCredentials=LibraryCredentialStore.Credentials(username,password,type)
    pending=true;ready=false;lastFailure="";binding.librarySubmit.isEnabled=false;binding.libraryRefreshCaptcha.isEnabled=false;binding.libraryLoginStatus.text="正在登录…"
    binding.libraryLoginWebView.evaluateJavascript(script){result->if(result!="true"&&!isFinishing&&!isDestroyed)fail("官网登录表单已变化，请重新获取验证码")}
  }
  override fun onDestroy(){submittedCredentials=null;handler.removeCallbacksAndMessages(null);binding.libraryUsername.text?.clear();binding.libraryPassword.text?.clear();binding.libraryCaptcha.text?.clear();binding.libraryLoginWebView.stopLoading();binding.libraryLoginWebView.destroy();binding.libraryCaptchaImage.setImageDrawable(null);captchaBitmap?.recycle();super.onDestroy()}
}
