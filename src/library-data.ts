import * as cheerio from "cheerio";
import { BrowserContext } from "playwright";
import { describeRequestFailure } from "./diagnostics.js";

// This is the OPAC address published by the library. Port 8080 serves HTTP.
export const LIBRARY_BASE = "http://202.119.83.14:8080/uopac/";
export const LIBRARY_SSO = `${LIBRARY_BASE}reader/login.php`; // current OPAC login; old hwthau.php returns 404
const clean = (s: string): string => s.replace(/\s+/g, " ").trim();
const address = (s: string): string => { const u = new URL(s); return `${u.protocol}//${u.host}${u.pathname}`; };
export class LibraryLoginRequiredError extends Error {
  constructor() { super("图书馆登录状态失效：请在图书馆登录页完成登录，再返回刷新借阅；不会自动重复提交密码。"); }
}
export interface LibraryBook {
  id: string; title: string; callNumber: string; description: string;
  copies: number; availableCopies: number; detailUrl: string;
}
export interface BookSearchResult { total: number; books: LibraryBook[] }
export interface LibraryHolding { callNumber: string; barcode: string; volume: string; location: string; status: string; returnLocation: string }
export interface LibraryLoan { barcode: string; title: string; borrowedAt: string; dueAt: string; location: string }

async function read(context: BrowserContext, initialUrl: string, sso = false): Promise<{ html: string; url: string }> {
  let url = initialUrl;
  for (let hop = 0;hop < 10;hop++) {
    const target = new URL(url);
    const trusted = target.origin === new URL(LIBRARY_BASE).origin || (sso && target.hostname === "ids.njust.edu.cn" && target.pathname.startsWith("/authserver/"));
    if (!trusted || target.username || target.password) throw new Error(`图书馆目标网站跳转错误：非预期地址 ${address(url)}。`);
    // Preserve the school-provided CAS URL and service scheme. Rewriting the
    // legacy HTTP flow to HTTPS makes IDS return an HTTPS callback to port 8080.
    try {
      const response = await context.request.get(url, { timeout: 25000, maxRedirects: 0 });
      if ([301, 302, 303, 307, 308].includes(response.status())) {
        const location = response.headers()["location"];
        if (!location) throw new Error(`图书馆目标网站跳转错误：HTTP ${response.status()} 未提供跳转地址。`);
        url = new URL(location, url).href;
        continue;
      }
      if (!response.ok()) throw new Error(`图书馆网站返回错误：HTTP ${response.status()}（${address(url)}）。`);
      const bytes = await response.body();
      const utf8 = new TextDecoder().decode(bytes);
      return { html: utf8.includes("\uFFFD") ? new TextDecoder("gb18030").decode(bytes) : utf8, url };
    } catch (error) {
      if (error instanceof Error && /图书馆网站返回错误|图书馆目标网站跳转错误/.test(error.message)) throw error;
      // Playwright errors may contain the full request URL (CAS tickets/CSRF).
      const detail = String(error instanceof Error ? error.message : error).split(/\nCall log:/)[0]!.replace(/https?:\/\/[^\s"'）)。]+/g, value => { try { return address(value); } catch { return "[地址已脱敏]"; } });
      throw describeRequestFailure("读取图书馆页面", address(url), new Error(detail));
    }
  }
  throw new Error("图书馆目标网站跳转错误：超过 10 次重定向，未重复提交密码。");
}

export function parseBookSearchHtml(html: string): BookSearchResult {
  const $ = cheerio.load(html);
  $("script,style").remove();
  const text = clean($("body").text());
  const count = text.match(/检索到\s*([\d,]+)\s*条/);
  const explicitEmpty = /没有检索到|未检索到|没有找到符合|没有找到您要|未找到相关|本馆没有您(?:要)?检索的图书/.test(text);
  if (!count && !explicitEmpty) throw new Error("图书搜索解析错误：缺少检索结果数量，可能返回登录页或异常页面。");
  const total = count ? Number(count[1]!.replace(/,/g, "")) : 0;
  const books = $("#search_book_list > li").toArray().map(item => {
    const link = $(item).find("h3 a[href]").first();
    const detail = new URL(link.attr("href") || "", `${LIBRARY_BASE}opac/`);
    const id = detail.searchParams.get("marc_no") || "";
    const title = clean(link.text()).replace(/^\d+\./, "");
    const heading = $(item).find("h3").clone(); heading.find("span,a").remove();
    const description = clean($(item).find("p").text());
    const copies = description.match(/馆藏复本[：:]\s*(\d+)/);
    const available = description.match(/可借复本[：:]\s*(\d+)/);
    if (detail.origin !== new URL(LIBRARY_BASE).origin || detail.pathname !== "/uopac/opac/item.php" || !id || !title || !copies || !available) throw new Error("图书搜索解析错误：书目链接或复本字段缺失。");
    return {
      id, title, callNumber: clean(heading.text()), description, copies: Number(copies[1]), availableCopies: Number(available[1]),
      detailUrl: `${LIBRARY_BASE}opac/item.php?${new URLSearchParams({ marc_no: id })}`
    };
  });
  if (total > 0 && !books.length) throw new Error("图书搜索解析错误：网站报告有结果，但书目列表缺失。");
  return { total, books };
}

export async function searchLibraryBooks(context: BrowserContext, keyword: string, page = 1): Promise<BookSearchResult> {
  if (!keyword.trim() || keyword.trim().length > 120 || !Number.isInteger(page) || page < 1 || page > 10000) throw new Error("图书搜索参数错误：检索词应为 1—120 个字符，页码必须为正整数。");
  const search = await read(context, `${LIBRARY_BASE}opac/search.php`);
  const $ = cheerio.load(search.html);
  const form = $("form").filter((_, f) => $(f).find("input[name=strText],input[name=q]").length > 0).first();
  if (!form.length || form.attr("method")?.toLowerCase() !== "get") throw new Error("图书搜索解析错误：检索表单缺失或提交方式变化。");
  const target = new URL(form.attr("action") || "", search.url);
  if (target.origin !== new URL(LIBRARY_BASE).origin || target.pathname !== "/uopac/opac/openlink.php") throw new Error("图书搜索解析错误：查询表单目标与预期不一致。");
  form.find("input[type=hidden][name]").each((_, e) => { target.searchParams.set($(e).attr("name")!, $(e).attr("value") || ""); });
  const modern = form.find("input[name=q]").length > 0;
  const params: Record<string,string> = modern ? {t:"title",m:"f",h:"1",q:keyword.trim(),d:"ALL",e:"on",pn:"20",i:"l",o:"CATA_DATE",da:"DESC",b:"ALL",page:String(page)} : { strSearchType: "title", match_flag: "forward", historyCount: "1", strText: keyword.trim(), doctype: "ALL", with_ebook: "on", displaypg: "20", showmode: "list", sort: "CATA_DATE", orderby: "desc", dept: "ALL", page: String(page) };
  Object.entries(params).forEach(([k, v]) => target.searchParams.set(k, v));
  const result = await read(context, target.href);
  return parseBookSearchHtml(result.html);
}

export function parseLibraryHoldings(html: string): LibraryHolding[] {
  const $ = cheerio.load(html); const table = $("table#item");
  if (!table.length || !/索书号/.test(table.find("tr").first().text()) || !/书刊状态/.test(table.find("tr").first().text())) throw new Error("图书馆藏解析错误：缺少索书号与书刊状态表格。");
  return table.find("tr").toArray().slice(1).map(row => {
    const cells = $(row).children("td").toArray().map(e => clean($(e).text()));
    if (cells.length !== 6) throw new Error("图书馆藏解析错误：馆藏列数变化，不能当成无馆藏。");
    return { callNumber: cells[0]!, barcode: cells[1]!, volume: cells[2]!, location: cells[3]!, status: cells[4]!, returnLocation: cells[5]! };
  });
}
export async function fetchLibraryHoldings(context: BrowserContext, book: LibraryBook): Promise<LibraryHolding[]> {
  const target = new URL(book.detailUrl);
  if (target.origin !== new URL(LIBRARY_BASE).origin || target.pathname !== "/uopac/opac/item.php" || !target.searchParams.get("marc_no")) throw new Error("图书馆藏参数错误：不是有效的学校书目详情地址。");
  return parseLibraryHoldings((await read(context, target.href)).html);
}

function isReaderAuthenticated(html: string): boolean {
  const $ = cheerio.load(html);
  return $("a[href]").toArray().some(a => /注销|退出/.test($(a).text()) && /logout/.test($(a).attr("href") || ""));
}
export async function ensureLibrarySession(context: BrowserContext): Promise<void> {
  const info = await read(context, `${LIBRARY_BASE}reader/redr_info.php`);
  if (isReaderAuthenticated(info.html)) return;
  const result = await read(context, LIBRARY_SSO, true);
  if (!isReaderAuthenticated(result.html)) throw new LibraryLoginRequiredError();
}
export function parseCurrentLoans(html: string): LibraryLoan[] {
  const $ = cheerio.load(html);
  if (!isReaderAuthenticated(html)) throw new LibraryLoginRequiredError();
  const text = clean($("body").text());
  if (!/当前借阅/.test(text)) throw new Error("借阅信息解析错误：缺少当前借阅标识。");
  if (/您的该项记录为空/.test(text)) return [];
  const table = $("table").toArray().find(t => /题名/.test($(t).find("tr").first().text()) && /应还/.test($(t).find("tr").first().text()));
  if (!table) throw new Error("借阅信息解析错误：缺少借阅表，且网站没有明确说明记录为空。");
  const rows = $(table).find("tr").toArray();
  const labels = $(rows[0]).children("td,th").toArray().map(e => clean($(e).text()));
  const index = (pattern: RegExp): number => labels.findIndex(t => pattern.test(t));
  const columns = { barcode: index(/条码/), title: index(/题名/), borrowedAt: index(/借阅日期|借书日期/), dueAt: index(/应还/), location: index(/馆藏地|借阅地/) };
  if (Object.values(columns).some(i => i < 0)) throw new Error("借阅信息解析错误：条码、题名、日期或馆藏地字段缺失。");
  return rows.slice(1).map(row => {
    const cells = $(row).children("td").toArray().map(e => clean($(e).text()));
    if (cells.length !== labels.length) throw new Error("借阅信息解析错误：借阅行与表头列数不一致。");
    return Object.fromEntries(Object.entries(columns).map(([k, i]) => [k, cells[i]!])) as unknown as LibraryLoan;
  });
}
export async function fetchCurrentLoans(context: BrowserContext): Promise<LibraryLoan[]> {
  await ensureLibrarySession(context);
  return parseCurrentLoans((await read(context, `${LIBRARY_BASE}reader/book_lst.php`)).html);
}
