import com.classsche.mobile.WifiPortalSession;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;

public class NetworkLoginCheck {
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    List<String> events=new ArrayList<>();String[] route={null};
    WifiPortalSession<String> session=new WifiPortalSession<>(network->{
      events.add("bind:"+network);
      if("invalid-wifi".equals(network))return false;
      route[0]=network;return true;
    },network->{events.add("open:"+network);return Unit.INSTANCE;},()->{events.add("stop");return Unit.INSTANCE;},state->{events.add("state:"+state);return Unit.INSTANCE;});
    check(events.isEmpty(),"No network or browser before Wi-Fi is available");
    session.available("mobile",false);session.unavailable();
    check(events.equals(List.of("state:NO_WIFI")),"No mobile binding or browser request when only cellular is present");
    events.clear();session.available("wifi-a",true);
    check(session.isReady()&&"wifi-a".equals(route[0]),"Wi-Fi bound before page loads");
    check(events.indexOf("bind:wifi-a")<events.indexOf("open:wifi-a"),"Binding precedes the first page request");
    int count=events.size();session.available("wifi-a",true);session.lost("old-wifi");
    check(events.size()==count,"Repeated capabilities do not reload or resubmit login");
    session.available("wifi-b",true);count=events.size();session.lost("wifi-a");
    check(events.size()==count&&"wifi-b".equals(route[0]),"Late loss of old network does not close the new page");
    session.lost("wifi-b");
    check(!session.isReady()&&"wifi-b".equals(route[0]),"Loss stops browser and keeps dead binding; no cellular fallback");
    check(!events.contains("bind:null"),"Lost Wi-Fi must not restore the default route");
    count=events.size();session.available("mobile",false);
    check(events.size()==count,"Mobile availability never opens a page");
    session.available("invalid-wifi",true);
    check(session.getState()==WifiPortalSession.State.BIND_FAILED&&!events.contains("open:invalid-wifi"),"Binding failure never loads login");
    session.available("wifi-a",true);check(session.isReady(),"Wi-Fi can recover after failure");
    events.clear();session.close();
    check(events.indexOf("stop")<events.indexOf("bind:null")&&route[0]==null,"Close destroys browser before clearing binding");
    count=events.size();session.available("late-wifi",true);session.lost("wifi-a");session.unavailable();session.close();
    check(events.size()==count,"Late callbacks after leaving are ignored");
    System.out.println("PASS Wi-Fi-only login: no cellular fallback, bind-before-load, no duplicate loads, loss, replacement, bind failure and cleanup");
  }
}
