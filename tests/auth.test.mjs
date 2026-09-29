import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { chromium } from "playwright";
import { resolveEndpoint, DEFAULT_LOGIN_URL, TEACHING_ORIGIN } from "../dist/endpoints.js";
import { looksLikeLoginPage, waitForManualLogin } from "../dist/login.js";
import { openAuthenticatedPage } from "../dist/authenticated-page.js";
import { openExamPage } from "../dist/exam-page.js";

let browser;
before(async () => { browser = await chromium.launch({ headless: true }); });
after(async () => { await browser?.close(); });
const config = {
  loginUrl: DEFAULT_LOGIN_URL, username: "test-user", password: "identity-test",
  manualLoginTimeoutMs: 1500,
  examQueryUrl: `${TEACHING_ORIGIN}/njlgdx/xsks/xsksap_query`,
  examListUrl: `${TEACHING_ORIGIN}/njlgdx/xsks/xsksap_list`
};

test("migrates both legacy IPs while preserving custom endpoints", () => {
  for (const ip of ["112", "113"]) {
    assert.equal(resolveEndpoint(`http://202.119.81.${ip}:8080`, DEFAULT_LOGIN_URL), DEFAULT_LOGIN_URL);
    assert.equal(resolveEndpoint(`http://202.119.81.${ip}:9080/njlgdx/kscj/cjcx_list?x=1`, ""),
      `${TEACHING_ORIGIN}/njlgdx/kscj/cjcx_list?x=1`);
  }
  assert.equal(resolveEndpoint("https://custom.example/login", DEFAULT_LOGIN_URL), "https://custom.example/login");
});

test("IDS fills visible duplicate IDs with PASSWORD and invokes the site's submit handler", async () => {
  const context = await browser.newContext();
  try {
    await context.route("**/*", route => route.fulfill({ contentType: "text/html", body: `
      <input id="username" hidden><input id="password" type="password" hidden>
      <input id="username"><input id="password" type="password">
      <a id="login_submit" href="#" onclick="sessionStorage.setItem('submitted',JSON.stringify(
        [...document.querySelectorAll('input')].map(e=>e.value)));this.remove();
        document.querySelectorAll('input').forEach(e=>e.remove());document.body.insertAdjacentHTML('beforeend','<table id=kbtable></table>');
        history.replaceState(null,'','/complete');return false;">Login</a>` }));
    const page = await context.newPage();
    await page.goto(DEFAULT_LOGIN_URL);
    await waitForManualLogin(page, config);
    assert.deepEqual(await page.evaluate(() => JSON.parse(sessionStorage.getItem("submitted"))),
      ["", "", "test-user", "identity-test"]);
  } finally { await context.close(); }
});

test("a missing form on an error page is not authentication success", async () => {
  const context = await browser.newContext();
  try {
    await context.route("**/*", route => route.fulfill({ contentType: "text/html", body: "<h1>Unavailable</h1>" }));
    const page = await context.newPage();
    await page.goto("https://error.njust.edu.cn/error.html");
    await assert.rejects(waitForManualLogin(page, { manualLoginTimeoutMs: 50 }), /登录超时/);
    await page.goto(DEFAULT_LOGIN_URL);
    assert.equal(await looksLikeLoginPage(page), true);
  } finally { await context.close(); }
});

test("reports the HTTP code and URL when a business page fails", async () => {
  const context = await browser.newContext();
  try {
    await context.route("**/*", route => route.fulfill({ status: 503, body: "Unavailable" }));
    const url = `${TEACHING_ORIGIN}/njlgdx/xskb/xskb_list.do`;
    await assert.rejects(openAuthenticatedPage(context, config, url, "#kbtable"),
      error => /HTTP 503/.test(error.message) && error.message.includes(url));
  } finally { await context.close(); }
});

test("reports an unexpected target redirect before parsing", async () => {
  const context = await browser.newContext();
  try {
    await context.route("**/*", route => route.fulfill({ contentType: "text/html", body:
      '<script>history.replaceState(null,"","/njlgdx/unexpected")</script><table id=kbtable></table>' }));
    const url = `${TEACHING_ORIGIN}/njlgdx/xskb/xskb_list.do`;
    await assert.rejects(openAuthenticatedPage(context, config, url, "#kbtable"), /目标网站跳转错误/);
  } finally { await context.close(); }
});

test("exchanges saved identity state via indexsso before re-opening the protected page", async () => {
  const context = await browser.newContext();
  let exchanged = false;
  const paths = [];
  try {
    await context.route("**/*", async route => {
      const path = new URL(route.request().url()).pathname;
      paths.push(path);
      if (path.endsWith("indexsso.jsp")) exchanged = true;
      await route.fulfill({ contentType: "text/html", body: exchanged
        ? '<table id="kbtable"></table>' : '<input name="USERNAME"><input type="password">' });
    });
    const url = `${TEACHING_ORIGIN}/njlgdx/xskb/xskb_list.do`;
    await openAuthenticatedPage(context, config, url, "#kbtable");
    assert.deepEqual(paths, ["/njlgdx/xskb/xskb_list.do", "/njlgdx/indexsso.jsp", "/njlgdx/xskb/xskb_list.do"]);
  } finally { await context.close(); }
});

test("exam query preserves the site's current semester and submits to configured list URL", async () => {
  const context = await browser.newContext();
  let submission;
  try {
    await context.route("**/*", async route => {
      if (route.request().method() === "POST") {
        submission = { url: route.request().url(), body: route.request().postData() };
        await route.fulfill({ contentType: "text/html", body: '<table id="dataList"></table>' });
      } else await route.fulfill({ contentType: "text/html", body:
        '<form name="ksapQueryForm" method="post"><select name="xnxqid"><option selected>2026-2027-1</option></select></form>' });
    });
    await openExamPage(context, config);
    assert.deepEqual(submission, { url: config.examListUrl, body: "xnxqid=2026-2027-1" });
  } finally { await context.close(); }
});
