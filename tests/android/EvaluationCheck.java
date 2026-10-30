import com.classsche.mobile.*;
import kotlin.Unit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import kotlin.Pair;
import java.util.*;

/** Offline evaluation server: every POST is recorded; no university or credentials are used. */
public final class EvaluationCheck {
  static final String ROOT=TeachingEvaluationParser.ENTRY;
  static final String BASE="https://bkjw.njust.edu.cn/njlgdx/xspj/";
  static final TeachingEvaluationParser P=TeachingEvaluationParser.INSTANCE;
  static final TeachingEvaluationParser.Settings SETTINGS=new TeachingEvaluationParser.Settings(0,1,"按课堂实际情况评价");
  static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
  static void fails(Runnable action,String message){try{action.run();throw new AssertionError("Expected failure: "+message);}catch(IllegalStateException|IllegalArgumentException e){check(e.getMessage().contains(message),e.toString());}}
  static Document doc(String body,String url){return Jsoup.parse(body,url);}
  static String form(int id,String token){return "<form id=Form1 method=post action='xspj_save.do?course="+id+"'><input type=hidden name=token value='"+token+"'><input type=hidden name=issubmit value=0><input disabled name=disabled value=no><table id=table1>"+
    "<tr><td>教学内容</td><td><label><input type=radio name=q1 value=A>很符合</label><label><input type=radio name=q1 value=B checked>较符合</label></td></tr>"+
    "<tr><td>综合评价</td><td><label><input type=radio name=q2 value=A>很符合</label><label><input type=radio name=q2 value=B>较符合</label></td></tr></table>"+
    "<textarea name=comment required></textarea><select name=tag><option value=x selected>已选</option></select></form>";}
  static final class Journal implements EvaluationJournal {
    final Set<String> unknown=new HashSet<>(); boolean failWrite;
    public boolean uncertain(String key){return unknown.contains(key);}
    public void markUncertain(String key){if(failWrite)throw new IllegalStateException("journal failure");unknown.add(key);}
    public void confirmed(String key){unknown.remove(key);}
  }
  static final class Server implements EvaluationTransport {
    final Set<Integer> done=new HashSet<>(); final List<Integer> posts=new ArrayList<>();
    final List<Map<String,String>> payloads=new ArrayList<>();
    int token=0; boolean timeout,unchanged,changed,changedTarget; Runnable onPost=()->{};
    public Document get(String url){
      if(url.equals(ROOT)){
        StringBuilder s=new StringBuilder("<h1>学生评教</h1><table><tr><th>课程名称</th><th>状态</th><th>操作</th></tr>");
        for(int i=1;i<=2;i++)s.append("<tr><td>课程").append(i).append("</td><td>").append(done.contains(i)?"已完成":"未完成").append("</td><td><a href='xspj_edit.do?course=").append(i).append("'>").append(done.contains(i)?"查看":"评价").append("</a></td></tr>");
        return doc(s+"</table>",url);
      }
      int id=Integer.parseInt(url.substring(url.lastIndexOf('=')+1));
      String html=form(id,"fresh"+(++token));if(changed)html=html.replace("很符合","新选项");
      if(changedTarget)html=html.replace("xspj_save.do?course="+id,"xspj_save.do?course=999");
      return doc(html,url);
    }
    public Document post(String url,List<Pair<String,String>> fields,String charset){
      int id=Integer.parseInt(url.substring(url.lastIndexOf('=')+1));posts.add(id);
      Map<String,String> values=new HashMap<>();for(Pair<String,String> p:fields)values.put(p.getFirst(),p.getSecond());payloads.add(values);
      check(values.get("token").equals("fresh"+token),"must submit fresh hidden token");
      check(!values.containsKey("disabled"),"disabled field leaked");
      check(values.get("q1").equals("A")&&values.get("q2").equals("B"),"wrong ratings");
      check(values.get("issubmit").equals("1")&&values.get("comment").equals(SETTINGS.getComment()),"flags/comment missing");
      onPost.run();
      if(timeout)throw new IllegalStateException("connection timeout");
      if(!unchanged)done.add(id);
      return doc("<script>alert('保存成功')</script>",url);
    }
  }
  static TeachingEvaluationEngine engine(Server server,Journal journal,String[] session,long[] clock){
    return new TeachingEvaluationEngine(server,journal,()->Unit.INSTANCE,()->session[0],()->clock[0]);
  }
  static TeachingEvaluationEngine.Plan scan(TeachingEvaluationEngine engine){return engine.scan(SETTINGS,p->Unit.INSTANCE);}
  static TeachingEvaluationEngine.Outcome submit(TeachingEvaluationEngine engine,String id){return engine.submit(id,p->Unit.INSTANCE);}
  public static void main(String[] args){
    check(P.allowed(ROOT)&&!P.allowed("https://bkjw.njust.edu.cn.evil.test/njlgdx/xspj/xspj_find.do"),"origin guard");
    check(P.key(BASE+"xspj_edit.do?course=1&token=old").equals(P.key(BASE+"xspj_edit.do?token=new&course=1")),"transient token must not change durable course identity");
    check(P.listing(doc("<h1>学生评教</h1><table><tr><td>是否已完成</td><td>操作</td></tr><tr><td>是</td><td><a href='xspj_edit.do?course=1'>评价</a></td></tr></table>",ROOT)).getCourses().get(0).getCompleted(),"school yes/no status column must be read");
    var f=P.form(doc(form(1,"old"),BASE+"xspj_edit.do?course=1"),SETTINGS);
    check(f.getChoices().get(0).contains("很符合")&&f.getChoices().get(1).contains("较符合"),"preview labels");
    fails(()->P.form(doc(form(1,"old"),BASE+"xspj_edit.do?course=1"),new TeachingEvaluationParser.Settings(4,1,"")),"只有");
    fails(()->P.form(doc(form(1,"old").replace("xspj_save.do?course=1","https://evil.test/post"),ROOT),SETTINGS),"跳转错误");
    fails(()->P.form(doc(form(1,"old").replace("method=post","method=get"),ROOT),SETTINGS),"POST");
    fails(()->P.form(doc(form(1,"old"),ROOT),new TeachingEvaluationParser.Settings(0,1,"")),"必填");
    fails(()->P.form(doc(form(1,"old").replace("</form>","<input name=yzm></form>"),ROOT),SETTINGS),"验证码");
    System.out.println("PASS evaluation parser: labels, bounds, foreign action, method, required comment");
    String[] session={"one"};long[] clock={1000};Server s=new Server();Journal j=new Journal();var e=engine(s,j,session,clock);var plan=scan(e);
    check(plan.getCourses().size()==2&&s.posts.isEmpty(),"preview must be read-only");
    var out=submit(e,plan.getId());check(s.posts.equals(List.of(1,2))&&out.getItems().stream().allMatch(i->i.getState().equals("success"))&&j.unknown.isEmpty(),"sequential verified submit");
    final var submittedEngine=e;final var usedPlan=plan;
    fails(()->submit(submittedEngine,usedPlan.getId()),"预览");check(s.posts.size()==2,"preview replay submitted again");
    System.out.println("PASS evaluation: read-only preview, fresh CSRF, configured ratings, sequential POST, server status, no replay");
    s=new Server();j=new Journal();e=engine(s,j,session,clock);plan=scan(e);s.done.add(1);out=submit(e,plan.getId());check(s.posts.equals(List.of(2))&&out.getItems().get(0).getState().equals("skipped"),"completed elsewhere must skip");
    System.out.println("PASS evaluation: fresh completed status skips an already evaluated course");
    for(boolean timeout:List.of(true,false)){
      s=new Server();j=new Journal();e=engine(s,j,session,clock);plan=scan(e);s.timeout=timeout;s.unchanged=!timeout;out=submit(e,plan.getId());
      check(s.posts.equals(List.of(1))&&out.getStopped()&&out.getItems().get(0).getState().equals("uncertain")&&j.unknown.size()==1,"uncertain must stop without retry");
      var next=scan(engine(s,j,session,clock));check(next.getCourses().size()==1&&!next.getNotices().isEmpty(),"restart must retain uncertain exclusion");
    }
    System.out.println("PASS evaluation: timeout/HTTP-200-without-status stay uncertain across restart; no duplicate POST");
    s=new Server();j=new Journal();e=engine(s,j,session,clock);plan=scan(e);s.changed=true;out=submit(e,plan.getId());check(s.posts.isEmpty()&&out.getStopped(),"changed form must not POST");
    s=new Server();j=new Journal();e=engine(s,j,session,clock);plan=scan(e);s.changedTarget=true;out=submit(e,plan.getId());check(s.posts.isEmpty()&&out.getStopped(),"changed action course must not POST");
    s=new Server();j=new Journal();e=engine(s,j,session,clock);plan=scan(e);j.failWrite=true;out=submit(e,plan.getId());check(s.posts.isEmpty(),"journal failure must prevent POST");
    s=new Server();j=new Journal();final var cancelEngine=engine(s,j,session,clock);plan=scan(cancelEngine);s.onPost=cancelEngine::cancel;out=submit(cancelEngine,plan.getId());check(s.posts.equals(List.of(1))&&out.getStopped(),"cancel should finish current result, stop later POSTs");
    s=new Server();j=new Journal();final var bound=engine(s,j,session,clock);final var boundPlan=scan(bound);session[0]="changed";fails(()->submit(bound,boundPlan.getId()),"会话");check(s.posts.isEmpty(),"account/session change must prevent POST");session[0]="one";
    final var expired=engine(new Server(),new Journal(),session,clock);final var expiredPlan=scan(expired);clock[0]+=600001;fails(()->submit(expired,expiredPlan.getId()),"过期");
    System.out.println("PASS evaluation: changed form, durable journal failure, cancellation, session change and expired preview");
  }
}
