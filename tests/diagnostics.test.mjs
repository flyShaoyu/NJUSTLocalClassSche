import assert from "node:assert/strict";
import test from "node:test";
import { describeRequestFailure, parseJsonStep } from "../dist/diagnostics.js";
import { parseTimetableHtml } from "../dist/html-parser.js";
import { parseScoreHtml } from "../dist/score-parser.js";

test("network timeout and DNS failure are identified separately", () => {
  assert.match(describeRequestFailure("打开课表", "https://example.edu", new Error("net::ERR_CONNECTION_TIMED_OUT")).message, /网页连接超时/);
  assert.match(describeRequestFailure("打开课表", "https://example.edu", new Error("net::ERR_NAME_NOT_RESOLVED")).message, /无法解析网站域名/);
});

test("malformed JSON and missing business tables report parsing errors", () => {
  assert.throws(() => parseJsonStep("成绩", "score-list.json", "{"), /解析错误.*score-list.json/);
  assert.throws(() => parseTimetableHtml("<html><body>Login</body></html>"), /课表解析错误/);
  assert.throws(() => parseScoreHtml("<html><body>Login</body></html>"), /成绩解析错误/);
  assert.deepEqual(parseScoreHtml("<table id=dataList><tr><th>成绩</th></tr></table>"), []);
});
