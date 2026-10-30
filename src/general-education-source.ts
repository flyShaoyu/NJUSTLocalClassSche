import * as cheerio from "cheerio";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, rename, unlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { Page } from "playwright";
import { describeRequestFailure } from "./diagnostics.js";

export const GENERAL_EDUCATION_URL = "https://jwc.njust.edu.cn/b1/7f/c1189a307583/page.htm";
export interface GeneralEducationSource {
  title: string;
  sourceUrl: string;
  imported: boolean;
  capturedOn: string;
  sourceSha256: string;
  categories: string[];
  courses: Array<{ courseCode: string; courseName: string; category: string }>;
}
const normalize = (text: string): string => text.replace(/\s+/g, " ").trim();

export const isPublicChallengeHtml = (html: string): boolean => {
  const $ = cheerio.load(html);
  const hasChallenge = $("script[r=m]").length > 0;
  return hasChallenge && $(".wp_articlecontent table").length === 0;
};

export const parseGeneralEducationSource = (html: string): GeneralEducationSource => {
  if (isPublicChallengeHtml(html)) throw new Error("网站防护校验未完成：返回的是验证脚本，不能当作课程分类表；原目录未覆盖");
  const $ = cheerio.load(html);
  const title = normalize($("title").text());
  if (!title.includes("通识教育选修课分类汇总")) throw new Error("分类表解析错误：网页标题不符，可能是网站错误页或异常跳转");
  const tables = $(".wp_articlecontent table").toArray();
  const table = tables.find(candidate => {
    const header = $(candidate).find("tr").first().children("td,th").toArray().map(cell => normalize($(cell).text()));
    return header.join("|") === "课程号|课程名|分类";
  });
  if (!table) throw new Error("分类表解析错误：缺少“课程号 / 课程名 / 分类”表头，原目录未覆盖");
  const rows = $(table).find("tr").toArray().slice(1);
  if (!rows.length) throw new Error("分类表解析错误：正文为空，原目录未覆盖");
  const courses = rows.map((row, index) => {
    const cells = $(row).children("td,th").toArray().map(cell => normalize($(cell).text()));
    if (cells.length !== 3 || !/^[A-Za-z0-9]{8}$/.test(cells[0] || "") || !cells[1] || !cells[2]) {
      throw new Error(`分类表解析错误：第 ${index + 2} 行缺少有效的课程号、名称或分类，原目录未覆盖`);
    }
    return { courseCode: cells[0]!, courseName: cells[1]!, category: cells[2]! };
  });
  const sourceCategories = [...new Set(courses.map(course => course.category))];
  const preferred = ["人文素养类", "经济社会类", "经济社会类（四史）", "艺术审美类", "自然科技类", "自然技术类"];
  const categories = [...preferred.filter(category => sourceCategories.includes(category)), ...sourceCategories.filter(category => !preferred.includes(category))];
  return {
    title, sourceUrl: GENERAL_EDUCATION_URL, imported: true,
    capturedOn: new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date()),
    sourceSha256: createHash("sha256").update(html).digest("hex"), categories, courses
  };
};

/** Navigation and DOM only: do not re-fetch the URL after the browser passes. */
export const readGeneralEducationSource = async (page: Page, timeoutMs = 25000): Promise<{
  html: string; source: GeneralEducationSource; statuses: number[];
}> => {
  const statuses: number[] = [];
  const listener = (response: import("playwright").Response) => {
    if (response.request().isNavigationRequest() && response.frame() === page.mainFrame()) statuses.push(response.status());
  };
  page.on("response", listener);
  try {
    let response;
    try { response = await page.goto(GENERAL_EDUCATION_URL, { waitUntil: "domcontentloaded", timeout: timeoutMs }); }
    catch (error) { throw describeRequestFailure("打开教务处分类表", GENERAL_EDUCATION_URL, error); }
    if (response && response.status() >= 400) throw new Error(`网站返回错误：HTTP ${response.status()}，jwc.njust.edu.cn；原目录未覆盖`);
    try { await page.locator(".wp_articlecontent table tr").nth(1).waitFor({ state: "attached", timeout: timeoutMs }); }
    catch (error) {
      const html = await page.content();
      if (statuses.includes(202) || isPublicChallengeHtml(html)) {
        throw new Error(`网站防护校验未通过：HTTP ${statuses.join("→") || "无响应"}。请用正常浏览器打开并保存网页后以 --file 导入；不重复提交请求，原目录未覆盖`, { cause: error });
      }
      const code = statuses[statuses.length - 1];
      if (code && code >= 400) throw new Error(`网站返回错误：HTTP ${code}，jwc.njust.edu.cn；原目录未覆盖`, { cause: error });
      throw new Error("分类表解析错误：等待正文超时，原目录未覆盖", { cause: error });
    }
    const actual = new URL(page.url()), expected = new URL(GENERAL_EDUCATION_URL);
    if (actual.origin !== expected.origin || actual.pathname !== expected.pathname) {
      throw new Error(`目标网站跳转错误：分类表离开预期页面（${actual.hostname}${actual.pathname}），原目录未覆盖`);
    }
    const html = await page.content();
    return { html, source: parseGeneralEducationSource(html), statuses };
  } finally { page.off("response", listener); }
};

export const renderGeneralEducationCatalog = (source: GeneralEducationSource): string => {
  const { courses, ...metadata } = source;
  const rows = courses.map(course => "  " + JSON.stringify(course) + ",").join("\n");
  const properties = JSON.stringify(metadata, null, 2).slice(0, -1).trimEnd();
  return "/** Public school catalogue; preserve source labels and conflicting rows. */\n" +
    "const courses: Array<{ courseCode: string; courseName: string; category: string }> = [\n" + rows + "\n];\n\n" +
    "export const GENERAL_EDUCATION_CATALOG = " + properties + ",\n  courses\n};\n";
};

export const writeGeneralEducationCatalog = async (html: string, outputFile: string): Promise<GeneralEducationSource> => {
  // Fully validate before opening or changing the existing catalogue.
  const source = parseGeneralEducationSource(html);
  const target = path.resolve(outputFile), temporary = target + "." + randomUUID() + ".tmp";
  await mkdir(path.dirname(target), { recursive: true });
  try {
    await writeFile(temporary, renderGeneralEducationCatalog(source), { encoding: "utf8", flag: "wx" });
    await rename(temporary, target);
  } finally { await unlink(temporary).catch(() => {}); }
  return source;
};
