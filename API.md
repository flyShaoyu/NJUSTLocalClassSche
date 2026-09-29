# 内部脚本与数据接口

这里的“API”是仓库内的命令、模块和 JSON 文件约定，不是公开 HTTP 服务。命令从仓库根目录运行，具体脚本以 [`package.json`](package.json) 为准。

## 命令入口

| 命令 | 输入与结果 |
| --- | --- |
| `npm run start` | `src/index.ts`：复用登录态或完成统一认证，依次抓取课表、考试安排、成绩和等级考试，生成 `artifacts/` 中的 HTML、JSON 与页面。 |
| `npm run fetch:exams` | 只进入考试安排页，保存原始 `exam-list.html`；需要结构化数据时继续运行 `npm run parse` 或完整抓取。 |
| `npm run fetch:scores` | 单独保存成绩原始 HTML、解析 JSON 与截图。 |
| `npm run fetch:level-exams` | 单独保存等级考试原始 HTML、解析 JSON 与截图。 |
| `npm run parse` | 用已保存的课表 HTML 重建课表 JSON/页面；存在考试或成绩 HTML 时也重解析它们。不重新登录，也不处理等级考试。 |
| `npm run render:ui` | 以 `timetable.json` 为必需输入，结合已有考试、成绩、等级考试 JSON 重绘本地页面。可选数据文件缺失时生成空页面；JSON 损坏时明确报错。 |
| `npm run export:android` | 从渲染结果生成不含个人数据的 Android 页面模板、公共图片、资源更新 ZIP 与清单。 |
| `npm run check` / `npm run build` | TypeScript 类型检查 / 编译到 `dist/`。编译后可运行 `node --test tests/*.test.mjs`。 |

`render:ui` 与 `export:android` 必须串行执行；构建 APK 应在导出之后。

## 登录与业务页面模块

- `src/config.ts` 读取 `.env`。`USERNAME` 和 `PASSWORD` 分别用于统一身份认证用户名及**智慧理工服务门户密码**；`SEMESTER` 可指定考试查询学期。站点 URL 有默认值，见 `.env.example`。
- `src/endpoints.ts` 定义新教务域名、统一认证和 SSO 入口；`src/browser.ts` 复用 `artifacts/storageState.json`。
- `src/login.ts` 填写认证表单并等待成功；`src/authenticated-page.ts` 验证响应码、实际跳转目标和业务页面选择器。`src/raw-page.ts` 用会话读取成绩类页面原始字节并处理编码。
- `src/diagnostics.ts` 为超时、域名解析、HTTP/跳转及解析步骤提供可定位的错误文案。异常业务页不会直接覆盖已有结构化缓存。

## 数据约定

| `artifacts/` 文件 | 产生模块与用途 |
| --- | --- |
| `storageState.json` | Playwright 保存的 Cookie/本地存储，含登录态，不能提交。 |
| `timetable.html`、`timetable.json`、`timetable-view.html` | 原始课表、`TimetableCourse[]`、本地课表页面。 |
| `exam-list.html`、`exam-list.json`、`exam-view.html` | 考试查询结果、`ExamArrangement[]`、考试页面。 |
| `score-list.html`、`score-list.json`、`score-view.html` | 成绩原始页、`ScoreRecord[]`、成绩统计页面。 |
| `level-exam-list.html`、`level-exam-list.json`、`level-exam-view.html` | 等级考试原始页、`LevelExamRecord[]`、等级考试页面。 |
| `home-view.html`、`resources/` | 首页页面及图片变体。 |
| `android-update/manifest.json`、`android-update/classsche-assets.zip` | Android 公共资源更新清单与包。 |

具体字段以 [`src/types.ts`](src/types.ts) 为准。课表 `courseType` 表示课程性质，`courseSequence` 是课程序号；不要互相代用。`credits` 为课程学分。修改这些字段时同步检查 Android 的 `TimetableCourse.kt`、`TimetableParser.kt` 和页面渲染器。

Android 的个人数据保存在应用私有目录，桌面 `artifacts/` 与 Android 运行时缓存不是同一个文件位置。详细构建与安全边界见 [ANDROID.md](ANDROID.md)。

Android 的应用版本检查由 `MainActivity.kt` 负责：有更新和无更新结果都按当前应用版本缓存 24 小时，手动重新获取时绕过缓存；APK 安装包由系统 `DownloadManager` 在后台继续下载，应用恢复时查询并显示进度，也可在弹窗中取消并清理文件。这与 `ResourceUpdateStore.kt` 的公共页面资源热更新是两条独立链路。

Android 登录与后台成绩同步只尝试统一认证，不再处理旧教务登录页的本地验证码；因此构建不再依赖 ML Kit 文本识别。
