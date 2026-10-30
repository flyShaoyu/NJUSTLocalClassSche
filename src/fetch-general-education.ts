import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { launchPublicSiteBrowser } from "./public-site-browser.js";
import { parseGeneralEducationSource, readGeneralEducationSource, writeGeneralEducationCatalog } from "./general-education-source.js";

const run = async (): Promise<void> => {
  const args = process.argv.slice(2);
  const fileIndex = args.indexOf("--file"), update = args.includes("--update-catalog");
  if (fileIndex >= 0 && (!args[fileIndex + 1] || args[fileIndex + 1]!.startsWith("--"))) throw new Error("--file 后需要保存的网页路径");
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--file") { i++; continue; }
    if (args[i] !== "--update-catalog") throw new Error(`未知参数：${args[i]}`);
  }
  let html: string;
  if (fileIndex >= 0) {
    const bytes = await readFile(path.resolve(args[fileIndex + 1]!));
    const utf8 = new TextDecoder("utf-8").decode(bytes);
    html = utf8.includes("\uFFFD") ? new TextDecoder("gb18030").decode(bytes) : utf8;
    console.log("读取本机保存的分类表，不发起网站请求。");
  } else {
    console.log("启动独立普通浏览器会话，等待校方脚本完成；不会提交账号密码。");
    const session = await launchPublicSiteBrowser();
    try {
      const page = session.context.pages()[0] || await session.context.newPage();
      const result = await readGeneralEducationSource(page);
      html = result.html;
      console.log(`网站响应：HTTP ${result.statuses.join("→")}，正文已验证。`);
    } finally { await session.close(); }
  }
  const source = parseGeneralEducationSource(html);
  const directory = path.resolve("artifacts/jwc-source");
  await mkdir(directory, { recursive: true });
  await writeFile(path.join(directory, "notice.html"), html, "utf8");
  await writeFile(path.join(directory, "catalog.json"), JSON.stringify(source, null, 2), "utf8");
  if (update) await writeGeneralEducationCatalog(html, "src/general-education-catalog.ts");
  const courseCodes = new Set(source.courses.map(course => course.courseCode));
  console.log(`取得 ${source.courses.length} 条分类记录、${courseCodes.size} 个课程号，保留原始类别与重复冲突。`);
  console.log(update ? "源码目录已原子更新；下一步依次 render:ui、export:android 并重新构建 APK。" : "结果保存到 artifacts/jwc-source/；加 --update-catalog 可更新源码目录。");
};

run().catch(error => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
