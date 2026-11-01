package com.classsche.mobile

/** UI-thread state machine. Keep a lost Wi-Fi binding until the browser is destroyed. */
internal class WifiPortalSession<N:Any>(
  private val bind:(N?)->Boolean,
  private val open:(N)->Unit,
  private val stop:()->Unit,
  private val changed:(State)->Unit
){
  enum class State { WAITING, READY, NO_WIFI, LOST, BIND_FAILED, CLOSED }
  var state=State.WAITING;private set
  var network:N?=null;private set
  val isReady:Boolean get()=state==State.READY
  private fun update(value:State){state=value;changed(value)}
  fun available(value:N,isWifi:Boolean){
    if(state==State.CLOSED)return
    if(!isWifi){lost(value);return}
    if(network==value&&isReady)return
    stop();network=value
    if(!bind(value)){update(State.BIND_FAILED);return}
    update(State.READY);open(value)
  }
  fun unavailable(){if(state!=State.CLOSED&&!isReady)update(State.NO_WIFI)}
  fun lost(value:N){
    if(state==State.CLOSED||network!=value)return
    stop();update(State.LOST)
    // Unbinding here would allow remaining browser traffic to use mobile data.
  }
  fun close(){
    if(state==State.CLOSED)return
    update(State.CLOSED);stop();bind(null);network=null
  }
}
