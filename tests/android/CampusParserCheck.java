import com.classsche.mobile.*;
import org.jsoup.Jsoup;
import java.nio.file.*;
import java.util.*;

public class CampusParserCheck {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void rejected(Runnable action) { try { action.run(); throw new AssertionError("Malformed input accepted"); } catch (IllegalArgumentException | IllegalStateException expected) {} }
  public static void main(String[] args) throws Exception {
    var parser = CampusDataParser.INSTANCE;
    check(LocalScheduleStore.INSTANCE.parseWeeks("1，3,5-8周").equals(List.of(1,3,5,6,7,8)), "Schedule weeks");
    check(LocalScheduleStore.INSTANCE.formatWeeks(List.of(1,2,3,5,7,8)).equals("1-3,5,7-8"), "Compact consecutive weeks");
    check(LocalScheduleStore.INSTANCE.formatWeeks(List.of(3,1,2,2)).equals("1-3"), "Compact sorted unique weeks");
    check(LocalScheduleStore.INSTANCE.formatWeeks(List.of()).isEmpty(), "Empty week formatting");
    LocalScheduleStore.INSTANCE.validateTime("10:20", "13:10");
    rejected(() -> LocalScheduleStore.INSTANCE.validateTime("13:10", "10:20"));
    rejected(() -> LocalScheduleStore.INSTANCE.parseWeeks("2-1"));
    rejected(() -> LocalScheduleStore.INSTANCE.parseWeeks("0,31"));
    rejected(() -> LocalScheduleStore.INSTANCE.parseWeeks("1-x"));
    System.out.println("PASS local schedule custom times and week validation");

    var rooms = parser.rooms(Jsoup.parse("<table id=dataList><tr><th></th><th colspan=2>星期一</th></tr><tr><td>节次</td><td tdvalue=010203>1</td><td tdvalue=0405>2</td></tr><tr jsbh=1><td>Ⅳ-A101(60/30)</td><td></td><td></td></tr><tr jsbh=2><td>Ⅳ-A102(60/30)</td><td>L</td><td></td></tr><tr><td>符号说明：L上课</td></tr></table>"));
    check(rooms.size()==2 && rooms.get(0).getFree() && !rooms.get(1).getFree(),"Room occupancy");
    rejected(() -> parser.rooms(Jsoup.parse("<h1>网站错误</h1>")));
    var onlyOne = new CampusRoom("1","Ⅳ-A101","60",true);
    var onlyTwo = new CampusRoom("2","Ⅳ-A102","60",true);
    check(parser.intersectFreeRooms(List.of(rooms,List.of(onlyOne,onlyTwo))).size()==1,"Intersection must exclude occupied rooms");
    check(parser.intersectFreeRooms(List.of(List.of(onlyOne),List.of(onlyTwo))).isEmpty(),"Disjoint intervals have no shared room");
    check(parser.intersectFreeRooms(List.of(List.of(onlyOne),List.of())).isEmpty(),"One empty interval makes intersection empty");
    check(parser.intersectFreeRooms(List.of(List.of(onlyOne,onlyOne),List.of(onlyOne))).size()==1,"Intersection removes duplicate room IDs");
    System.out.println("PASS all selected classroom intervals must be free; occupied, empty, duplicate cases verified");
    check(parser.books(Jsoup.parse("本馆没有您要检索的图书")).isEmpty(),"No books");
    rejected(() -> parser.bookTotal(Jsoup.parse("服务器故障")));
    check(parser.loans(Jsoup.parse("<a href=logout.php>注销</a>当前借阅 您的该项记录为空！<input name=captcha> ")).isEmpty(),"Empty authenticated loans");
    rejected(() -> parser.loans(Jsoup.parse("登录 <input type=password>")));
    String tiles="<a href=logout.php>注销</a><div class=profile>TEST_PRIVATE_PROFILE</div><div class=infobox-container>";
    for(int i=0;i<4;i++)tiles+="<div class=infobox><div class=infobox-data><span class=infobox-data-number>"+i+"</span><div class=infobox-content>模拟状态"+i+"</div></div></div>";
    tiles+="</div><div class=charts>TEST_PRIVATE_HISTORY</div>";
    var status=parser.readerSummary(Jsoup.parse(tiles));
    check(status.size()==4 && status.get(0).getFirst().equals("模拟状态0") && status.get(3).getSecond().equals("3"),"Four status tiles");
    check(status.stream().noneMatch(x->x.toString().contains("PRIVATE")),"Profile and history must not leak into summary");
    check(parser.readerSummary(Jsoup.parse(tiles.replace(">3</span>","></span>"))).get(3).getSecond().equals("—"),"An explicitly empty website value stays unknown, not zero");
    final String validTiles=tiles;
    rejected(()->parser.readerSummary(Jsoup.parse(validTiles.replace("infobox-data-number","unexpected-field"))));
    rejected(()->parser.readerSummary(Jsoup.parse("<input type=password>")));
    rejected(()->parser.readerSummary(Jsoup.parse("<a href=logout.php>注销</a><div class=infobox-container></div>")));
    System.out.println("PASS library four-tile summary excludes profile/history, rejects missing login and incomplete fields");
    var teacher = parser.teacherCourses(Jsoup.parse("<table id=dataList><tr><th>开课时间</th></tr><tr><td>1</td><td>班级</td><td>30</td><td>K1</td><td>测试课程</td><td>张老师</td><td>学院</td><td>1080910</td><td>Ⅳ-A101</td><td>1-8</td><td>单周</td></tr></table>"));
    check(teacher.size()==1 && teacher.get(0).getPeriods().equals("8-10节") && teacher.get(0).getWeeks().equals("1,3,5,7周"),"Teacher time / parity");
    rejected(() -> parser.teacherCourses(Jsoup.parse("<table id=dataList><tr><th>错误页面</th></tr></table>")));
    System.out.println("PASS Android room, library and teacher parsers reject error pages");

    var general = GeneralEducationCatalogParser.INSTANCE;
    String source = "<title>通识教育选修课分类汇总（2022版）</title><div class=wp_articlecontent><table><tr><td>课程号</td><td>课程名</td><td>分类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>自然科技类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>人文素养类</td></tr></table></div>";
    var catalogue = general.parse(source);
    check(catalogue.getCourses().size()==2 && catalogue.getCourses().get(0).getCourseCode().equals("00W00184"), "General catalogue leading zero / conflict");
    rejected(() -> general.parse("<script r=m>challenge</script>"));
    rejected(() -> general.parse(source.replace("<td>00W00184</td>", "<td>184</td>")));
    rejected(() -> general.parse(source.replace("<td>分类</td>", "<td>错误列</td>")));
    System.out.println("PASS Android general catalogue rejects challenges and malformed rows");

    if(args.length>=2) {
      var courses = parser.publicCourses(Jsoup.parse(Files.readString(Path.of(args[0]))));
      var teachers = parser.teacherCourses(Jsoup.parse(Files.readString(Path.of(args[1]))));
      check(!courses.isEmpty() && courses.stream().allMatch(c -> !c.getTeacher().isBlank() && !c.getClassroom().isBlank()),"Actual course structure");
      check(teachers.size()==4,"Actual teacher timetable");
      System.out.println("PASS saved school course and teacher HTML ("+courses.size()+" / "+teachers.size()+")");
    }
    if(args.length>=3) {
      var actual = general.parse(Files.readString(Path.of(args[2])));
      check(actual.getCourses().size()==742, "Actual general catalogue row count");
      check(actual.getCourses().stream().filter(c -> c.getCourseCode().equals("00W00184")).count()==2, "Actual conflicting rows");
      System.out.println("PASS actual general catalogue 742 rows with conflicts retained");
    }
  }
}
