# `src/` 源码说明

这套 TypeScript 脚本在仓库根目录运行，负责统一认证、教务页面抓取、HTML 解析、网页生成及 Android 公共资源导出。总览与跨端数据边界见根目录 [FILE-INDEX.md](../FILE-INDEX.md) 和 [API.md](../API.md)。

## 执行入口

| 文件 | 对应命令与用途 |
| --- | --- |
| `index.ts` | `npm run start`：完整抓取课表、考试、成绩和等级考试，生成 HTML/JSON/页面。 |
| `fetch-exams.ts` | `npm run fetch:exams`：单独抓考试原始 HTML。 |
| `fetch-scores.ts`、`fetch-level-exams.ts` | 单独抓成绩/等级考试，保存 HTML、JSON 和截图。 |
| `parse-html.ts` | `npm run parse`：离线重解析课表以及已有考试、成绩 HTML，不处理等级考试。 |
| `render-ui.ts` | `npm run render:ui`：用已有 JSON 生成首页和四类业务页面。 |
| `export-android.ts` | `npm run export:android`：导出空数据模板、公共图片及资源更新包。 |

`render:ui` 完成后才能运行 `export:android`，两者不要并行。

## 文件职责

- `config.ts`、`endpoints.ts`、`types.ts`：环境变量、站点地址、路径及共享数据模型。统一认证密码只使用 `.env` 的 `PASSWORD`。
- `browser.ts`、`login.ts`、`authenticated-page.ts`：Playwright 会话、登录表单、教务 SSO 和业务页有效性检查。
- `diagnostics.ts`、`raw-page.ts`：分步骤错误提示，以及成绩类页面原始 HTML/编码处理。
- `timetable-page.ts`、`exam-page.ts`、`score-page.ts`、`level-exam-page.ts`：业务页面抓取。
- `html-parser.ts`、`exam-parser.ts`、`score-parser.ts`、`level-exam-parser.ts`：HTML → JSON。课表字段变化要同步核对 Android `Timetable*` 文件。
- `home-page-ui.ts` / `home-page-ui-script.ts`、`timetable-ui.ts` / `timetable-ui-script.ts`、`exam-ui.ts`、`score-ui.ts`、`level-exam-ui.ts`：本地网页与交互。
- `fs-utils.ts`、`logger.ts`：文件和日志辅助函数。
- `home-ui.ts`、`home-ui-script.ts`：旧版首页实现，已从 TypeScript 编译中排除，主流程不用。

抓取页为 HTTP 200 仍可能是登录表单；解析器必须检查业务表格，再写结构化数据。正常的空考试列表可以解析为 `[]`，缺少 `#dataList` 则是网站结构或登录错误。缓存、密码和 Cookie 不应提交。
