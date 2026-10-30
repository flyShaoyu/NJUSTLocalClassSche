import * as cheerio from "cheerio";
import { BrowserContext } from "playwright";
import { TEACHING_ORIGIN } from "./endpoints.js";
import { describeRequestFailure } from "./diagnostics.js";

const QUERY = `${TEACHING_ORIGIN}/njlgdx/kbxx/jsjy_query`;
const RESULT = `${TEACHING_ORIGIN}/njlgdx/kbxx/jsjy_query2`;
const FILTERS = `${TEACHING_ORIGIN}/njlgdx/kbxx/jsjy_processAjax`;
const CLASSROOM_FILTERS = `${TEACHING_ORIGIN}/njlgdx/kbcx/getJxlByAjax`;
const clean = (value: string): string => value.replace(/\s+/g, " ").trim();
export interface RoomOption { value: string; label: string }
export interface EmptyRoomQuery {
  semester: string; campus: string; building?: string;
  week: number; weekday: number; startPeriod: number; endPeriod: number;
}
export interface RoomSlot {
  weekday: string; label: string; periods: number[];
  state: "free" | "occupied" | "unknown"; marker: string;
}
export interface RoomAvailability {
  id: string; name: string; capacity?: number; examCapacity?: number; slots: RoomSlot[];
}
export interface RoomFilters {
  semesters: RoomOption[]; campuses: RoomOption[]; buildings: RoomOption[];
  buildingSource?: string; warnings?: string[];
}

export const parseRoomAvailability = (html: string): RoomAvailability[] => {
  const $ = cheerio.load(html);
  const table = $("#dataList").first();
  if (!table.length || !table.find("tr").first().text().includes("星期")) {
    throw new Error("空闲教室解析错误：缺少星期及节次表头，可能返回登录页或异常页面。");
  }
  const rows = table.find("tr").toArray();
  const days: string[] = [];
  $(rows[0]).children("th,td").toArray().slice(1).forEach(cell => {
    const span = Number($(cell).attr("colspan") || 1);
    if (!Number.isInteger(span) || span < 1 || span > 14) throw new Error("空闲教室解析错误：星期列跨度异常。");
    for (let i = 0;i < span;i++) days.push(clean($(cell).text()));
  });
  const headers = $(rows[1]).children("td,th").toArray().slice(1).map(cell => {
    const value = $(cell).attr("tdvalue") || "";
    if (!/^(?:\d{2})+$/.test(value)) throw new Error("空闲教室解析错误：节次编码缺失或格式变化。");
    return { label: clean($(cell).text()), periods: value.match(/\d{2}/g)!.map(Number) };
  });
  if (!headers.length || headers.length !== days.length) throw new Error("空闲教室解析错误：星期与节次列数不一致。");
  const dataRows = rows.slice(2).filter(row => $(row).attr("jsbh") || $(row).find("input[name=jsids]").length);
  if (!dataRows.length && rows.slice(2).some(row => {
    const text = clean($(row).text());
    return text && !/^符号说明[：:]/.test(text) && !/暂无|没有|无数据|未查询到|未找到|共\s*0/.test(text);
  })) throw new Error("空闲教室解析错误：存在无法识别的数据行，不能当作空结果。");
  return dataRows.map(row => {
    const cells = $(row).children("td").toArray();
    if (cells.length !== headers.length + 1) throw new Error("空闲教室解析错误：教室行与表头列数不一致。");
    const nameText = clean($(cells[0]).text());
    const capacity = nameText.match(/\((\d+)\/(\d+)\)$/);
    const id = $(row).attr("jsbh") || $(row).find("input[name=jsids]").attr("value") || "";
    if (!id || !nameText) throw new Error("空闲教室解析错误：教室编号或名称为空。");
    const slots: RoomSlot[] = cells.slice(1).map((cell, i) => {
      const marker = clean($(cell).text());
      const knownOccupied = /^[ＬLＧGΚKＸXＪJ◆]+$/.test(marker);
      const decoration = $(cell).find("img,svg").length || /background|bgcolor/i.test($(cell).attr("style") || "") || $(cell).attr("bgcolor");
      return {
        weekday: days[i]!, ...headers[i]!, marker,
        state: !marker && !decoration ? "free" : knownOccupied ? "occupied" : "unknown"
      };
    });
    return {
      id, name: capacity ? nameText.slice(0, capacity.index).trim() : nameText,
      ...(capacity ? { capacity: Number(capacity[1]), examCapacity: Number(capacity[2]) } : {}), slots
    };
  });
};

function validateQuery(query: EmptyRoomQuery): void {
  if (!/^\d{4}-\d{4}-[123]$/.test(query.semester) || !query.campus) throw new Error("空闲教室查询参数错误：必须指定学期和校区。");
  for (const [name, value, max] of [["周次", query.week, 30], ["星期", query.weekday, 7], ["开始节次", query.startPeriod, 14], ["结束节次", query.endPeriod, 14]] as const) {
    if (!Number.isInteger(value) || value < 1 || value > max) throw new Error(`空闲教室查询参数错误：${name}应为 1—${max} 的整数。`);
  }
  if (query.startPeriod > query.endPeriod) throw new Error("空闲教室查询参数错误：开始节次不能晚于结束节次。");
}

async function request(context: BrowserContext, url: string, form?: Record<string, string>): Promise<string> {
  try {
    const response = form ? await context.request.post(url, { form, timeout: 25000 }) : await context.request.get(url, { timeout: 25000 });
    if (!response.ok()) throw new Error(`网站返回错误：HTTP ${response.status()}（${url}）。`);
    if (new URL(response.url()).origin !== TEACHING_ORIGIN || new URL(response.url()).pathname !== new URL(url).pathname) {
      throw new Error(`目标网站跳转错误：${url} 未返回原查询页面，请先恢复教务登录态。`);
    }
    const bytes = await response.body();
    const utf8 = new TextDecoder().decode(bytes);
    return utf8.includes("\uFFFD") ? new TextDecoder("gb18030").decode(bytes) : utf8;
  } catch (error) {
    if (error instanceof Error && /网站返回错误|目标网站跳转错误/.test(error.message)) throw error;
    const detail = String(error instanceof Error ? error.message : error).split(/\nCall log:/)[0]!;
    throw describeRequestFailure("查询空闲教室", url, new Error(detail));
  }
}

function requireForm(html: string): cheerio.CheerioAPI {
  const $ = cheerio.load(html);
  if (!$("form[action='/njlgdx/kbxx/jsjy_query2'] select[name=xnxqh]").length) {
    throw new Error("空闲教室解析错误：查询表单缺失，可能需要重新登录。");
  }
  return $;
}
const options = ($: cheerio.CheerioAPI, name: string): RoomOption[] => $(`select[name=${name}] option`).toArray()
  .map(o => ({ value: $(o).attr("value") || "", label: clean($(o).text()) })).filter(o => o.value);

export const fetchRoomFilters = async (context: BrowserContext, campus?: string): Promise<RoomFilters> => {
  const $ = requireForm(await request(context, QUERY));
  const campuses = options($, "xqbh");
  if (campus && !campuses.some(o => o.value === campus)) throw new Error("空闲教室查询参数错误：网站不存在所选校区。");
  let buildings: RoomOption[] = [];
  let buildingSource: string | undefined;
  const warnings: string[] = [];
  if (campus) {
    const parse = (text: string): RoomOption[] => {
      let data: unknown;
      try { data = JSON.parse(text.trim()); }
      catch {
        if (/非法访问/.test(text)) throw new Error("校方教学楼联动接口返回“非法访问”（HTTP 200）");
        throw new Error("教学楼列表解析错误：网站未返回有效 JSON。");
      }
      if (!Array.isArray(data) || data.some(o => !o || typeof o.dm !== "string" || typeof o.dmmc !== "string")) throw new Error("教学楼列表解析错误：字段与预期不一致，可能需要恢复教务登录态。");
      return data.map(o => ({ value: o.dm, label: o.dmmc }));
    };
    try {
      buildings = parse(await request(context, FILTERS, { xqid: campus, requestType: "jxl" }));
      buildingSource = FILTERS;
    } catch (primary) {
      // The school's classroom timetable publishes a second building lookup.
      // Use its normal query endpoint; never infer IDs from classroom names.
      try {
        buildings = parse(await request(context, CLASSROOM_FILTERS, { xqid: campus }));
        buildingSource = CLASSROOM_FILTERS;
        warnings.push(`${primary instanceof Error ? primary.message : String(primary)}；已使用校方教室课表的教学楼列表。`);
      } catch (fallback) {
        throw new Error(`获取教学楼列表失败：原接口：${primary instanceof Error ? primary.message : String(primary)}；教室课表接口：${fallback instanceof Error ? fallback.message : String(fallback)}`);
      }
    }
  }
  return { semesters: options($, "xnxqh"), campuses, buildings, buildingSource, warnings };
};

export const fetchEmptyRooms = async (context: BrowserContext, query: EmptyRoomQuery): Promise<RoomAvailability[]> => {
  validateQuery(query);
  const $ = requireForm(await request(context, QUERY));
  const form: Record<string, string> = {};
  $("form[action='/njlgdx/kbxx/jsjy_query2'] input[type=hidden][name]").each((_, input) => { form[$(input).attr("name")!] = $(input).attr("value") || ""; });
  const selected: Record<string, string> = {
    xnxqh: query.semester, xqbh: query.campus, jxlbh: query.building || "", jxqbh: "", jsbh: "", bjfh: "=", rnrs: "", jszt: "5",
    zc: String(query.week), zc2: String(query.week), xq: String(query.weekday), xq2: String(query.weekday),
    jc: String(query.startPeriod).padStart(2, "0"), jc2: String(query.endPeriod).padStart(2, "0")
  };
  for (const name of ["xnxqh", "xqbh", "zc", "zc2", "xq", "xq2", "jc", "jc2", "jszt"]) {
    if (!options($, name).some(o => o.value === selected[name])) throw new Error(`空闲教室查询参数错误：网站不支持所选 ${name}。`);
  }
  if (query.building && !(await fetchRoomFilters(context, query.campus)).buildings.some(b => b.value === query.building)) throw new Error("空闲教室查询参数错误：教学楼不属于所选校区。");
  return parseRoomAvailability(await request(context, RESULT, { ...form, ...selected }));
};
