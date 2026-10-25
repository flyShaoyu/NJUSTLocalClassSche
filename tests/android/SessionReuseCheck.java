import com.classsche.mobile.HeadlessLoginClient;
import com.classsche.mobile.LoginCookieStore;
import com.classsche.mobile.ScoreSyncTiming;
import com.classsche.mobile.ScoreSyncTriggerPolicy;
import kotlin.Unit;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the compiled production HTTP client without connecting to the university. */
public final class SessionReuseCheck {
  static final String IDS = "https://ids.njust.edu.cn/authserver/";
  static final String LOGIN = "https://bkjw.njust.edu.cn/njlgdx/indexsso.jsp";
  static final String TABLE = "https://bkjw.njust.edu.cn/njlgdx/xskb/xskb_list.do";
  static final String FORM = "<form id='pwdFromId' action='/authserver/login' method='post'><input type='password' name='password'><input id='pwdEncryptSalt' value='abcdefghijklmnop'><input name='execution' value='fixture'></form>";
  static final String TABLE_HTML = "<table id='kbtable'><tr><td>fixture</td></tr></table>";
  static String scenario;
  static int passwordPosts, captchaChecks, identityGets, timetableGets;

  static void check(boolean condition, String detail) {
    if (!condition) throw new AssertionError(scenario + ": " + detail);
  }

  static final class SharedStore implements LoginCookieStore {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    int flushes;
    public synchronized String read(String url) {
      try { return String.join("; ", cookies.get(URI.create(url), Map.of()).getOrDefault("Cookie", List.of())); }
      catch (IOException error) { throw new RuntimeException(error); }
    }
    public synchronized void receive(String url, Map<String, ? extends List<String>> headers) {
      try { cookies.put(URI.create(url), new HashMap<>(headers)); }
      catch (IOException error) { throw new RuntimeException(error); }
    }
    public void seed(String url, String header) { receive(url, Map.of("Set-Cookie", List.of(header))); }
    public void flush() { flushes++; }
  }

  static final class Connection extends HttpURLConnection {
    int code = -1;
    String body = "", location;
    Map<String,List<String>> headers = Map.of();
    Connection(URL url) { super(url); }
    public void connect() {}
    public void disconnect() {}
    public boolean usingProxy() { return false; }
    public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    boolean hasCookie(String value) { return Objects.toString(getRequestProperty("Cookie"), "").contains(value); }
    void response(int status, String content) { code = status; body = content; }
    void redirect(String target) { response(302, ""); location = target; }
    public int getResponseCode() throws IOException {
      if (code != -1) return code;
      String path = url.getPath();
      if (url.getHost().equals("ids.njust.edu.cn")) check(!hasCookie("teacher-fixture"), "teaching cookie leaked to IDS");
      else check(!hasCookie("ids-fixture"), "IDS cookie leaked to teaching host");
      if (path.endsWith("xskb_list.do")) {
        timetableGets++;
        if (scenario.equals("timeout")) throw new SocketTimeoutException("fixture timeout");
        if (scenario.equals("http500")) response(500, "site failure");
        else if (scenario.equals("malformed")) response(200, "<h1>site maintenance</h1>");
        else if (hasCookie("JSESSIONID=teacher-fixture")) response(200, TABLE_HTML);
        else if (scenario.equals("expired401")) response(401, "");
        else response(200, "<input type='password'>");
      } else if (path.endsWith("indexsso.jsp")) {
        redirect(IDS + "login?service=https%3A%2F%2Fbkjw.njust.edu.cn%2Fnjlgdx%2Findexsso.jsp");
      } else if (path.endsWith("checkNeedCaptcha.htl")) {
        captchaChecks++;
        check(hasCookie("JSESSIONID=ids-fixture"), "IDS challenge session not retained");
        response(200, "{\"isNeed\":" + scenario.equals("captcha") + "}");
      } else if (path.equals("/authserver/login")) {
        if (method.equals("POST")) {
          passwordPosts++;
          if (scenario.equals("empty401")) response(401, "");
          else if (scenario.equals("frozen")) response(401, FORM + "<div id='showErrorTip'>该帐号已经被冻结</div>");
          else {
            redirect("https://bkjw.njust.edu.cn/njlgdx/framework/main.jsp");
            headers = Map.of("Set-Cookie", List.of("TGC=valid-sso; Path=/authserver; Secure; HttpOnly"));
          }
        } else {
          identityGets++;
          if (hasCookie("TGC=valid-sso")) redirect("https://bkjw.njust.edu.cn/njlgdx/framework/main.jsp");
          else response(200, FORM);
          headers = Map.of("Set-Cookie", List.of("JSESSIONID=ids-fixture; Path=/authserver; Secure; HttpOnly"));
        }
      } else if (path.endsWith("framework/main.jsp")) {
        response(200, "<h1>Teaching portal</h1>");
        headers = Map.of("Set-Cookie", List.of("JSESSIONID=teacher-fixture; Path=/njlgdx; Secure; HttpOnly"));
      } else throw new AssertionError("Unexpected request: " + url.getHost() + path);
      return code;
    }
    public InputStream getInputStream() throws IOException { getResponseCode(); return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
    public InputStream getErrorStream() { return body.isEmpty() ? null : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
    public Map<String,List<String>> getHeaderFields() { return headers; }
    public String getHeaderField(String name) { return name.equals("Location") ? location : null; }
  }

  static HeadlessLoginClient client(SharedStore store) {
    return new HeadlessLoginClient((scope,status,message) -> {
      check(!message.contains("teacher-fixture") && !message.contains("ids-fixture") && !message.contains("fixture-password"), "secret logged");
      return Unit.INSTANCE;
    }, "fixture-agent", store);
  }

  static void login(SharedStore store, String password) {
    check(client(store).login(LOGIN, TABLE, "fixture-student", password, "").getTimetableHtml().contains("kbtable"), "invalid timetable");
  }

  static void reset(String name) { scenario=name; passwordPosts=0; captchaChecks=0; identityGets=0; timetableGets=0; }

  public static void main(String[] args) throws Exception {
    URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
      protected URLConnection openConnection(URL url) { return new Connection(url); }
    } : null);

    reset("reuse");
    SharedStore saved = new SharedStore();
    saved.seed(TABLE, "JSESSIONID=teacher-fixture; Path=/njlgdx; Secure; HttpOnly");
    login(saved, ""); login(saved, "");
    check(passwordPosts==0 && captchaChecks==0 && identityGets==0 && timetableGets==2, "valid cookie must bypass authentication, even without a saved password");
    System.out.println("PASS valid session reused by two new clients without password");

    for (String name : List.of("relogin", "expired401")) {
      reset(name);
      SharedStore store = new SharedStore();
      login(store, "fixture-password");
      check(passwordPosts==1 && captchaChecks==1, "expired session must authenticate once");
      login(store, "fixture-password");
      check(passwordPosts==1 && captchaChecks==1, "next client must reuse persisted session");
      check(store.read(TABLE).contains("teacher-fixture") && store.read(IDS).contains("ids-fixture") && store.flushes==2, "session persistence or scope lost");
      store.seed(TABLE, "JSESSIONID=deleted; Path=/njlgdx; Secure; Max-Age=0");
      check(!store.read(TABLE).contains("teacher-fixture"), "deleted cookie revived");
      System.out.println("PASS " + name + ": one password submission, next sync reuses cookie, deletion honored");
    }

    reset("sso");
    SharedStore sso = new SharedStore();
    sso.seed(IDS, "TGC=valid-sso; Path=/authserver; Secure; HttpOnly");
    login(sso, "");
    check(passwordPosts==0 && captchaChecks==0 && identityGets==1, "CAS SSO should restore teaching session without password");
    System.out.println("PASS existing CAS session restores teaching session without password");

    for (String name : List.of("timeout", "http500", "malformed")) {
      reset(name);
      try { login(new SharedStore(), "fixture-password"); throw new AssertionError("Expected failure"); }
      catch (Exception expected) { check(passwordPosts==0 && identityGets==0, "network/parse failures must not trigger credential login"); }
      System.out.println("PASS " + name + ": no password fallback");
    }

    reset("captcha");
    try { login(new SharedStore(), "fixture-password"); throw new AssertionError("Expected CAPTCHA"); }
    catch (HeadlessLoginClient.CaptchaRequiredException expected) {
      check(passwordPosts==0 && !expected.getSessionCookies().isEmpty(), "CAPTCHA must stop password submission and retain session");
    }
    System.out.println("PASS CAPTCHA hands off session without password submission");

    reset("frozen");
    try { login(new SharedStore(), "fixture-password"); throw new AssertionError("Expected frozen account"); }
    catch (HeadlessLoginClient.AuthenticationRejectedException expected) {
      check(passwordPosts==1 && expected.getMessage().contains("被冻结") && expected.getMessage().contains("HTTP 401"), "authentication rejection must be typed and retain the site error");
    }
    System.out.println("PASS frozen account returns typed rejection after one attempt");

    reset("empty401");
    try { login(new SharedStore(), "fixture-password"); throw new AssertionError("Expected authentication rejection"); }
    catch (HeadlessLoginClient.AuthenticationRejectedException expected) {
      check(passwordPosts==1 && expected.getMessage().contains("HTTP 401"), "empty authentication failure must also stop automatic retries");
    }
    System.out.println("PASS empty HTTP 401 returns typed rejection after one attempt");

    reset("concurrent");
    SharedStore shared = new SharedStore();
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = workers.submit(() -> login(shared, "fixture-password"));
      Future<?> second = workers.submit(() -> login(shared, "fixture-password"));
      first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
      check(passwordPosts==1, "overlapping syncs must not authenticate twice");
    } finally { workers.shutdownNow(); }
    System.out.println("PASS simultaneous refresh/sync share one authentication attempt");

    long interval = 7_200_000L, last = 10_000_000L;
    ScoreSyncTiming timing = ScoreSyncTiming.INSTANCE;
    check(timing.remainingDelayMillis(0, last, interval)==0, "first sync blocked");
    for (long delta : new long[]{20_000L, 61_000L, 100_000L}) check(timing.remainingDelayMillis(last, last+delta, interval)==interval-delta, "background delay lost elapsed time");
    check(timing.remainingDelayMillis(last,last+interval,interval)==0, "due sync blocked");
    check(timing.remainingDelayMillis(last,last-60_000L,interval)==interval, "clock rollback permits repeated login");
    check(timing.remainingDelayMillis(last,last+interval,86_400_000L)>0, "24h interval ignored");
    System.out.println("PASS timing: background delay, due boundary, clock rollback, 24h interval");

    reset("startup-policy");
    ScoreSyncTriggerPolicy process = new ScoreSyncTriggerPolicy();
    check(process.skipReason(true, false, null, null, interval)==null, "cold start must ignore background interval");
    for (long remaining : new long[]{interval, 0L, 86_400_000L}) {
      check(process.skipReason(true, false, null, null, remaining)!=null, "foreground return or Activity recreation repeated startup sync");
    }
    check(process.skipReason(false, false, null, null, interval)!=null, "alarm bypassed configured interval");
    check(process.skipReason(false, false, null, null, 0L)==null, "startup gate blocked a due background alarm");
    check(new ScoreSyncTriggerPolicy().skipReason(true, false, null, null, interval)==null, "new process must sync despite persisted timestamp");

    ScoreSyncTriggerPolicy busy = new ScoreSyncTriggerPolicy();
    check(busy.skipReason(true, true, null, null, 0L)!=null, "startup overlapped active background sync");
    check(busy.skipReason(true, false, null, null, 0L)!=null, "returning after in-progress sync triggered another startup attempt");
    for (String restriction : List.of("disabled", "not-wifi")) {
      ScoreSyncTriggerPolicy restricted = new ScoreSyncTriggerPolicy();
      check(restricted.skipReason(true, false, restriction, null, 0L)!=null, "startup bypassed settings: " + restriction);
      check(restricted.skipReason(true, false, null, null, 0L)!=null, "settings return retried skipped startup");
    }
    check(new ScoreSyncTriggerPolicy().skipReason(true, false, null, "frozen account", 0L)!=null, "new process bypassed authentication pause");

    ScoreSyncTriggerPolicy concurrentStartup = new ScoreSyncTriggerPolicy();
    ExecutorService startupWorkers = Executors.newFixedThreadPool(4);
    try {
      List<Future<Boolean>> attempts = new ArrayList<>();
      for (int i=0; i<8; i++) attempts.add(startupWorkers.submit(() -> concurrentStartup.skipReason(true, false, null, null, interval)==null));
      int allowed = 0;
      for (Future<Boolean> attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) allowed++;
      check(allowed==1, "concurrent foreground triggers repeated startup sync");
    } finally { startupWorkers.shutdownNow(); }
    System.out.println("PASS startup sync once per process: resume/recreation, restart, settings/auth pause, alarms, concurrency");
  }
}
