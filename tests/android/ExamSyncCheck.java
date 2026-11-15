import com.classsche.mobile.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;

public class ExamSyncCheck {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static ExamArrangement exam(int index,String time,String room,String seat){return new ExamArrangement(index,"2026-2027-1期末","A01","测试课程",time,room,seat,"老师","");}
  static void fails(Runnable run,String text){try{run.run();throw new AssertionError("Expected failure: "+text);}catch(IllegalStateException e){check(e.getMessage().contains(text),e.getMessage());}}
  public static void main(String[] args)throws Exception {
    ExamArrangement a=exam(1,"2026-12-01 08:00~10:00","I-101","12");
    var policy=ExamUpdatePolicy.INSTANCE;
    check(policy.changes(null,List.of(a)).isEmpty(),"First pull establishes baseline");
    check(policy.changes(List.of(),List.of(a)).get(0).getKind()==ExamUpdatePolicy.Kind.ADDED,"Known empty cache must detect new exam");
    check(policy.changes(List.of(a),List.of(a.copy(8,a.getExamSession(),a.getCourseCode(),a.getCourseName(),a.getExamTime(),a.getExamRoom(),a.getSeatNumber(),"新老师","changed raw"))).isEmpty(),"Row number and teacher enrichment do not notify");
    for(ExamArrangement changed:List.of(exam(1,"2026-12-02 08:00~10:00","I-101","12"),exam(1,a.getExamTime(),"I-102","12"),exam(1,a.getExamTime(),"I-101","13"))){
      var diff=policy.changes(List.of(a),List.of(changed));check(diff.size()==1&&diff.get(0).getKind()==ExamUpdatePolicy.Kind.CHANGED,"Time, room or seat changes notify once");
      check(policy.changes(List.of(changed),List.of(changed)).isEmpty(),"Repeated poll does not notify again");
    }
    check(policy.changes(List.of(a),List.of()).get(0).getKind()==ExamUpdatePolicy.Kind.REMOVED,"Valid empty response removes old entry");
    var b=exam(2,"2026-12-03 08:00~10:00","I-201","2");
    check(policy.changes(List.of(a,b),List.of(b,a)).isEmpty(),"Order does not matter, including same-course multiple exams");
    check(policy.changes(List.of(a,b),List.of(b,exam(1,a.getExamTime(),"I-103","12"))).size()==1,"Exact matches removed before changed entries paired");
    System.out.println("PASS exam changes: baseline, empty-to-new, adjustments, removal, reorder and duplicate suppression");

    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    AtomicInteger requests=new AtomicInteger(),mode=new AtomicInteger();
    List<String> errors=new ArrayList<>();
    String[] cookie={"session=existing"};
    LoginCookieStore store=new LoginCookieStore(){
      public String read(String url){return cookie[0];}
      public void receive(String url,Map<String,? extends List<String>> headers){headers.forEach((k,v)->{if(k.equalsIgnoreCase("Set-Cookie"))cookie[0]=v.get(0).split(";")[0];});}
      public void flush(){}
    };
    server.createContext("/",exchange->{
      requests.incrementAndGet();String path=exchange.getRequestURI().getPath();String body;
      int status=200;
      if(path.equals("/query")){
        if(!"session=existing".equals(exchange.getRequestHeaders().getFirst("Cookie")))errors.add("Query did not reuse teaching cookie");
        exchange.getResponseHeaders().add("Set-Cookie","session=rotated; Path=/");
        body="<form name='ksapQueryForm' action='https://untrusted.invalid/collect' method='post'><input name='token' value='校方值'><select name='xnxqid'><option value='2025-2026-2'>old</option><option selected value='2026-2027-1'>current</option></select><input disabled name='ignored' value='x'></form>";
      }else if(path.equals("/list")){
        if(!"POST".equals(exchange.getRequestMethod()))errors.add("Query method lost");
        if(!"session=rotated".equals(exchange.getRequestHeaders().getFirst("Cookie")))errors.add("Updated teaching cookie not used");
        String posted=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        if(!posted.contains("xnxqid=2026-2027-1")||!posted.contains("token=%E6%A0%A1%E6%96%B9%E5%80%BC")||posted.contains("ignored")||posted.contains("password"))errors.add("Form fields invalid: "+posted);
        body="<table id='dataList'><tr><th>序号</th><th>考试场次</th><th>课程号</th><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>座位</th></tr>";
        if(mode.get()==0)body+="<tr><td>1</td><td>期末</td><td>A01</td><td>测试课程</td><td>2026-12-01 08:00~10:00</td><td>I-101</td><td>12</td></tr>";
        if(mode.get()==2)body="<form><input type='password'></form>";
        if(mode.get()==3){status=302;exchange.getResponseHeaders().add("Location","https://untrusted.invalid/auth?ticket=hidden");}
        if(mode.get()==4)status=503;
        if(mode.get()==5)body+="<tr><td>unexpected malformed row</td></tr>";
        body+="</table>";
      }else{status=404;body="unexpected path";errors.add("Unexpected request "+path);}
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/html;charset=UTF-8");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.start();
    try{
      ExamQueryClient client=new ExamQueryClient((scope,status,message)->Unit.INSTANCE,store,base+"/query",base+"/list");
      var records=client.fetch(List.of());check(records.size()==1&&records.get(0).getSeatNumber().equals("12"),"Parse exam time room and seat");
      cookie[0]="session=existing";mode.set(1);check(client.fetch(List.of()).isEmpty(),"Validated empty list is successful");
      for(int value:new int[]{2,3,4,5}){
        mode.set(value);cookie[0]="session=existing";
        fails(()->client.fetch(List.of()),switch(value){case 2->"表格";case 3->"跳转错误";case 4->"HTTP 503";default->"字段不足";});
      }
      check(errors.isEmpty(),errors.toString());check(requests.get()==12,"Exactly two school requests per sync; no login or redirect requests");
      System.out.println("PASS exam fetch: existing session, cookie rotation, current school semester, fixed query target, valid empty list and failure isolation");
    }finally{server.stop(0);}
  }
}
