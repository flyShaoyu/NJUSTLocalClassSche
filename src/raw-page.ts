import { Page } from "playwright";
import { describeRequestFailure, errorMessage } from "./diagnostics.js";
import { logStep } from "./logger.js";

export const readRawBusinessHtml = async (page: Page, label: string): Promise<string> => {
  const expectedUrl = page.url();
  logStep(`Fetching raw ${label} HTML with charset fallback decoding from ${expectedUrl}`);
  try {
    return await page.evaluate(async (url) => {
      const response = await fetch(url, { credentials: "include" });
      if (!response.ok) {
        throw new Error(`网站返回错误：HTTP ${response.status}，地址 ${response.url}`);
      }
      const expected = new URL(url);
      const actual = new URL(response.url);
      if (expected.origin !== actual.origin || expected.pathname !== actual.pathname) {
        throw new Error(`目标网站跳转错误：预期 ${expected.origin}${expected.pathname}，实际 ${actual.origin}${actual.pathname}`);
      }
      const bytes = new Uint8Array(await response.arrayBuffer());
      const utf8 = new TextDecoder("utf-8").decode(bytes);
      if (!utf8.includes("\uFFFD")) return utf8;
      for (const encoding of ["gb18030", "gbk"]) {
        try { return new TextDecoder(encoding).decode(bytes); } catch { /* try next encoding */ }
      }
      return new TextDecoder().decode(bytes);
    }, expectedUrl);
  } catch (error) {
    const message = errorMessage(error);
    if (/网站返回错误|目标网站跳转错误/.test(message)) {
      throw new Error(`${label}页面获取失败：${message}`, { cause: error });
    }
    throw describeRequestFailure(`获取${label}页面`, expectedUrl, error);
  }
};
