# `src/` 源码说明

这套 TypeScript 脚本在仓库根目录运行，负责统一认证、教务页面抓取、HTML 解析、网页生成及 Android 公共资源导出。总览与跨端数据边界见根目录 [FILE-INDEX.md](../FILE-INDEX.md) 和 [API.md](../API.md)。

## 执行入口

| 文件 | 对应命令与用途 |
| --- | --- |
| `index.ts` | `npm run start`：完整抓取课表、考试、成绩和等级考试，生成 HTML/JSON/页面。 |
| `verify-home.ts` | `npm run verify:home`：首页十项数据验证，不改 UI、不提交密码，结果放在独立的 `artifacts/home-data/`。 |
| `fetch-exams.ts` | `npm run fetch:exams`：单独抓考试原始 HTML。 |
| `fetch-scores.ts`、`fetch-level-exams.ts` | 单独抓成绩/等级考试，保存 HTML、JSON 和截图。 |
| `parse-html.ts` | `npm run parse`：离线重解析课表以及已有考试、成绩 HTML，不处理等级考试。 |
| `render-ui.ts` | `npm run render:ui`：用已有 JSON 生成首页和四类业务页面。 |
| `export-android.ts` | `npm run export:android`：导出空数据模板、公共图片及资源更新包。 |

`render:ui` 完成后才能运行 `export:android`，两者不要并行。

## 文件职责

“小工具”的选课 / 抢课可进入本地课程分类目录。`enrollment-catalog-ui.ts` 的 `buildEnrollmentCatalog/filterEnrollmentCatalog` 按课程号去重、保留原表分类及冲突，提供名称/课程号搜索、分类与待核对筛选、30 门分页；不依赖教务的课程浏览页。它与成绩页共用 `general-education-catalog.ts` 和同源本机分类更正；Android 注入同一手机分类缓存。`enrollment-catalog-v1` 供旧资源回退检查，`CourseEnrollmentFeature.kt` 将浏览与提交分开，真实选课调用仍被原生拦截。

`campus-tools-ui.ts` 的 `showToolbox` 为首页“小工具”提供课程搜索、课程评教两张卡片，通过 `CampusBridge.openTool` 打开独立子页，返回保留目录；旧模板缺少 `toolbox-menu-v1` 时回退 APK 模板。评教包含设置、逐门预览、进度和停止按钮；`evaluation.scan/submit` 仅经 Android 原生桥接执行。前端不持有 Cookie 或表单票据，确认弹窗、POST、防重复记录及结果核实在 Kotlin。`home-page-ui.ts` / `home-page-ui-script.ts` 与原生首页同步“小工具”入口。

- `config.ts`、`endpoints.ts`、`types.ts`：环境变量、站点地址、路径及共享数据模型。统一认证密码只使用 `.env` 的 `PASSWORD`。
- `browser.ts`、`login.ts`、`authenticated-page.ts`：Playwright 会话、登录表单、教务 SSO 和业务页有效性检查。
- `diagnostics.ts`、`raw-page.ts`：分步骤错误提示，以及成绩类页面原始 HTML/编码处理。
- `timetable-page.ts`、`exam-page.ts`、`score-page.ts`、`level-exam-page.ts`：业务页面抓取。
- `html-parser.ts`、`exam-parser.ts`、`score-parser.ts`、`level-exam-parser.ts`：HTML → JSON。课表字段变化要同步核对 Android `Timetable*` 文件。
- `empty-room.ts`：空教室筛选和结果解析，原教学楼接口异常时复用学校教室课表的真实联动接口。
- `library-data.ts`：公开图书检索、分页、馆藏状态与借阅查询；以已有 IDS Cookie 恢复图书馆 SSO，不提交密码或操作续借／预约。
- `local-courses.ts`：桌面验证用手动课程独立存储；Android 自由时间日程使用 `LocalScheduleStore.kt`，并非此文件。
- `common-sites.ts`、`campus-tools-ui.ts`：常用网站清单和 Android 本地校园工具 UI（日程、教室、图书馆、课程及教师搜索），由导出脚本生成空数据模板。
- `home-page-ui.ts` / `home-page-ui-script.ts`、`timetable-ui.ts` / `timetable-ui-script.ts`、`exam-ui.ts`、`score-ui.ts`、`level-exam-ui.ts`：本地网页与交互。
- `general-education-ui.ts` / `general-education-pages.ts` / `general-education-catalog.ts`：成绩页分类学分、集中分栏编辑和可搜索分类表（742 行），精确匹配、已通过学分去重、本机更正；包含计划外候选与必须手动指定的辅修类别。`general-education-drag.ts` 提供长按课程或拖右侧三条杠跨分类移动，支持自动滚动和取消，记录沿用本机分类存储统一保存。嵌套页面通过 `classsche://score-section/` 切换原生标题、保存动作和返回；目录保留原始六类标签和冲突，不做名称模糊猜测。
- `public-site-browser.ts`、`general-education-source.ts`、`fetch-general-education.ts`：公开教务处网页使用普通浏览器进程、独立配置目录及本机 CDP 读取；校验正文后解析，不重复 HTTP 获取。`npm run fetch:general-education` 支持本地 `--file` 和显式 `--update-catalog`，失败保留原目录；与教务登录会话独立。
- `fs-utils.ts`、`logger.ts`：文件和日志辅助函数。
- `home-ui.ts`、`home-ui-script.ts`：旧版首页实现，已从 TypeScript 编译中排除，主流程不用。

抓取页为 HTTP 200 仍可能是登录表单；解析器必须检查业务表格，再写结构化数据。正常的空考试列表可以解析为 `[]`，缺少 `#dataList` 则是网站结构或登录错误。缓存、密码和 Cookie 不应提交。
