import fs from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright";
import { loadConfig } from "./config.js";
import { parseTimetableHtml } from "./html-parser.js";
import { parseExamArrangementHtml } from "./exam-parser.js";
import { parseScoreHtml } from "./score-parser.js";
import { parseLevelExamHtml } from "./level-exam-parser.js";
import { openTimetablePage } from "./timetable-page.js";
import { openExamPage } from "./exam-page.js";
import { openScorePage, readScorePageHtml } from "./score-page.js";
import { openLevelExamPage, readLevelExamPageHtml } from "./level-exam-page.js";
import { fetchEmptyRooms, fetchRoomFilters } from "./empty-room.js";
import { createLocalCourse, mergeLocalCourses, readLocalCourses, saveLocalCourses } from "./local-courses.js";
import { fetchCurrentLoans, fetchLibraryHoldings, LibraryLoginRequiredError, searchLibraryBooks } from "./library-data.js";
import { COMMON_SITES } from "./common-sites.js";
import { TimetableCourse } from "./types.js";
import { withStep } from "./diagnostics.js";

interface Check { key: string; title: string; status: "passed" | "failed" | "blocked"; detail: string; count?: number }
async function main(): Promise<void> {
  const args = new Map<string, string>();
  for (let i = 2;i < process.argv.length;i += 2) {
    const key = process.argv[i]!; const value = process.argv[i + 1];
    if (!/^--(?:state|out|semester|campus|week|weekday|start|end|book)$/.test(key) || !value || value.startsWith("--")) throw new Error(`未知或不完整的参数：${key}`);
    args.set(key.slice(2), value);
  }
  const output = path.resolve(args.get("out") || "artifacts/home-data");
  const defaultState = path.join(output, "storage-state.json");
  const stateFile = path.resolve(args.get("state") || (await fs.access(defaultState).then(() => defaultState).catch(() => "artifacts/storageState.json")));
  await fs.mkdir(output, { recursive: true });
  const config = { ...loadConfig(), username: undefined, password: undefined, semester: args.get("semester") || "2026-2027-1", manualLoginTimeoutMs: 1000 };
  const browser = await chromium.launch({ headless: true });
  try {
    const userAgent = `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${browser.version()} Safari/537.36`;
    const context = await browser.newContext({ storageState: await fs.access(stateFile).then(() => stateFile).catch(() => undefined), userAgent });
    const publicContext = await browser.newContext({ userAgent });
    context.setDefaultTimeout(20000);
    context.setDefaultNavigationTimeout(25000);
    let passwordSubmissions = 0;
    await context.route("**/authserver/login**", async route => {
      if (route.request().method() === "POST") { passwordSubmissions++; await route.abort(); }
      else await route.continue();
    });
    const checks: Check[] = [];
    const safeError = (error: unknown): string => String(error instanceof Error ? error.message : error)
      .split(/\nCall log:/)[0]!.replace(/https?:\/\/[^\s"'）)。]+/g, url => { try { const u = new URL(url); return u.origin + u.pathname; } catch { return "[地址已脱敏]"; } });
    async function check(key: string, title: string, action: () => Promise<{ detail: string; count?: number }>): Promise<boolean> {
      let result: Check;
      try { result = { key, title, status: "passed", ...await action() }; }
      catch (error) { result = { key, title, status: error instanceof LibraryLoginRequiredError ? "blocked" : "failed", detail: safeError(error) }; }
      checks.push(result); console.log(JSON.stringify(result));
      return result.status === "passed";
    }
    async function save(name: string, value: unknown): Promise<void> {
      const file = path.join(output, name); const temp = file + ".tmp";
      await fs.writeFile(temp, JSON.stringify(value, null, 2) + "\n");
      await fs.rename(temp, file);
    }

    let courses: TimetableCourse[] = [];
    const fetched: Record<string, unknown[]> = {};
    await check("schedule", "课表查询", async () => {
      const page = await openTimetablePage(context, config);
      try { courses = parseTimetableHtml(await page.content()); fetched.courses = courses; return { count: courses.length, detail: "复用 Cookie 抓取并通过现有课表解析器" }; }
      finally { await page.close(); }
    });
    await check("exam", "考试安排", async () => {
      const page = await openExamPage(context, config);
      try { const data = parseExamArrangementHtml(await page.content(), courses); fetched.exams = data; return { count: data.length, detail: "提交所选学期查询并解析；有效空列表也保留明确结果" }; }
      finally { await page.close(); }
    });
    await check("score", "成绩查询", async () => {
      const page = await openScorePage(context, config);
      try { const data = parseScoreHtml(await readScorePageHtml(page)); fetched.scores = data; return { count: data.length, detail: "复用 Cookie 抓取并通过现有成绩解析器" }; }
      finally { await page.close(); }
    });
    await check("level", "等级考试", async () => {
      const page = await openLevelExamPage(context, config);
      try { const data = parseLevelExamHtml(await readLevelExamPageHtml(page)); fetched.levelExams = data; return { count: data.length, detail: "复用 Cookie 抓取并通过现有等级考试解析器" }; }
      finally { await page.close(); }
    });
    await check("refresh", "更新数据", async () => {
      if (Object.keys(fetched).length !== 4) throw new Error("更新数据失败：有业务页获取或解析失败，原快照未覆盖。");
      await save("snapshot.json", { fetchedAt: new Date().toISOString(), semester: config.semester, ...fetched });
      const roundTrip = JSON.parse(await fs.readFile(path.join(output, "snapshot.json"), "utf8"));
      if (Object.entries(fetched).some(([key, data]) => JSON.stringify(roundTrip[key]) !== JSON.stringify(data))) throw new Error("更新数据读回校验失败：保存内容与原始解析结果不同。");
      return { detail: "四类数据完整获取后原子保存到独立快照，读回一致；不覆盖原 App/桌面缓存" };
    });
    await check("add", "添加课表", async () => {
      const file = path.join(output, "manual-courses-smoke.json");
      // Dedicated smoke-test file: never edit the user's actual manual-course file.
      const original = await readLocalCourses(file);
      const added = createLocalCourse({ semester: config.semester, courseName: "本地流程验证课程", weekday: 1, startPeriod: 1, endPeriod: 2, weeks: [1, 2] });
      try {
        await saveLocalCourses(file, [...original, added]);
        const stored = await readLocalCourses(file);
        if (!stored.some(c => c.id === added.id)) throw new Error("添加课程保存后读回失败");
        const edited = createLocalCourse({ ...added, classroom: "验证教室", weeks: [3, 4] }, added.id);
        await saveLocalCourses(file, stored.map(c => c.id === edited.id ? edited : c));
        const readBack = await readLocalCourses(file);
        const merged = mergeLocalCourses(courses, readBack.filter(c => c.id === edited.id), config.semester);
        if (merged.length !== courses.length + 1 || !merged.some(c => c.courseName === edited.courseName && c.classroom === "验证教室")) throw new Error("本地课程编辑或合并失败");
      } finally { await saveLocalCourses(file, original); }
      if ((await readLocalCourses(file)).some(c => c.id === added.id)) throw new Error("本地验证课程删除失败");
      return { detail: "新增、保存读回、编辑、按学期合并、删除均通过；验证记录已清理" };
    });
    await check("room", "空闲教室", async () => {
      const campus = args.get("campus") || "01";
      const filters = await fetchRoomFilters(context, campus);
      await save("room-filters.json", filters);
      const rooms = await fetchEmptyRooms(context, {
        semester: config.semester, campus,
        week: Number(args.get("week") || 10), weekday: Number(args.get("weekday") || 3), startPeriod: Number(args.get("start") || 1), endPeriod: Number(args.get("end") || 3)
      });
      await save("empty-rooms.json", rooms);
      return { count: rooms.length, detail: `校区、教学楼联动与结果解析通过；${filters.buildings.length} 个教学楼，${rooms.filter(r => r.slots.every(s => s.state === "free")).length} 个教室在返回时段均为空闲${filters.warnings?.length ? '；' + filters.warnings.join('；') : ''}` };
    });
    await check("site", "常用网站", async () => {
      const sites = [];
      for (const site of COMMON_SITES) {
        const r = await publicContext.request.get(site.url, { timeout: 20000, maxRedirects: 0 });
        if (r.status() >= 400) throw new Error(`${site.title}连接失败：HTTP ${r.status()}（${site.url}）。`);
        sites.push({ ...site, http: r.status(), needsBrowser: r.status() === 202 });
      }
      await save("common-sites.json", sites);
      return { count: sites.length, detail: "链接地址及服务响应有效；HTTP 202 的图书馆官网须用正常浏览器加载，不能作为书目数据源" };
    });
    await check("library", "图书搜索", async () => {
      const keyword = args.get("book") || "高等数学";
      const result = await withStep("检索图书首页", () => searchLibraryBooks(publicContext, keyword));
      await save("book-search.json", result);
      if (!result.books.length) throw new Error("图书搜索验证未完成：检索词没有结果，尚未验证详情获取。");
      const holdings = await withStep("读取图书馆藏详情", () => fetchLibraryHoldings(publicContext, result.books[0]!));
      const next = await withStep("读取图书检索第二页", () => searchLibraryBooks(publicContext, keyword, 2));
      if (result.total > 20 && (!next.books.length || next.books[0]?.id === result.books[0]?.id)) throw new Error("图书搜索分页错误：第二页未变化。");
      const empty = await withStep("验证图书无匹配结果", () => searchLibraryBooks(publicContext, "周三课表"));
      if (empty.total || empty.books.length) throw new Error("图书搜索空结果验证失败。");
      await save("book-search.json", result); await save("book-holdings.json", holdings);
      return { count: result.books.length, detail: `共 ${result.total} 条；检索、第二页、无匹配结果、单本详情均通过，首本 ${holdings.length} 条馆藏；无需登录` };
    });
    await check("borrow", "借阅信息", async () => {
      const loans = await fetchCurrentLoans(context);
      await save("current-loans.json", loans);
      return { count: loans.length, detail: loans.length ? "复用统一认证恢复图书馆 SSO 并解析当前借阅，未执行续借或预约" : "复用统一认证恢复图书馆 SSO，学校明确返回当前借阅记录为空；没有当作登录失败" };
    });
    await context.storageState({ path: defaultState });
    await save("report.json", { checkedAt: new Date().toISOString(), passwordSubmissions, checks });
    if (passwordSubmissions) throw new Error("验证中出现了非预期的密码提交，已拦截。");
    if (checks.some(c => c.status !== "passed")) process.exitCode = 1;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; });
