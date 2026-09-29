# 项目文件索引

本索引按“需要修改什么”组织。项目有两条主要链路：`src/` 负责登录、抓取、解析和生成网页；`android/` 负责本地登录、缓存展示、后台同步及通知。数据流是 `教务网站 → artifacts/ → Android assets → APK`。

## 给新对话的最小上下文

先让新对话读取**本文件**和 [`AGENT.md`](AGENT.md)，再指出这次要改的功能；只需按下表进一步读取相关源码，无需上传整个项目。索引描述的是代码职责，具体运行状态以当前工作区和最新测试结果为准。

- 桌面抓取入口为 `npm run start`（`src/index.ts`）；单项命令见 `package.json`。统一认证从 `ids.njust.edu.cn` 进入，教务业务页在 `bkjw.njust.edu.cn/njlgdx/`。`.env` 只用 `USERNAME` 和 `PASSWORD`（智慧理工服务门户密码）；入口和验证记录见 [`LOGIN-MIGRATION.md`](LOGIN-MIGRATION.md)。
- Playwright 登录态保存在被忽略的 `artifacts/storageState.json`；Android 使用 WebView Cookie 与独立的 Keystore 加密密码。两端不会直接共享 Cookie 文件。
- `artifacts/*.html` 是抓取的原始页面，`*.json` 是解析结果，`*-view.html` 是生成页面。Android 导出会清理个人数据，只把页面模板和公共资源写入 `assets`。
- 课表 JSON 的字段和 Android 本地缓存展示是关键兼容边界。修改解析器时检查 [`src/types.ts`](src/types.ts) 和 Android 的 `Timetable*` 文件。
- 当前工作区可能有未提交改动；新对话应看 `git status --short`。不要覆盖 `.env`、登录态、缓存或用户改动。不得在聊天、日志、提交中展示密码、完整 Cookie 或票据。
- 修改网页后依次运行 `npm run render:ui`、`npm run export:android`，不可并行。修改 Android 后在 `android/` 运行 `.\gradlew.bat :app:assembleDebug`。

提问可直接写“先看 `FILE-INDEX.md`，然后修改某功能”，并补充出错步骤、原始错误文案、目标页面和预期行为。这样新对话只需展开相应文件。

## 常见修改入口

| 需求 | 优先查看 |
| --- | --- |
| 登录入口、SSO、登录态复用 | [`src/endpoints.ts`](src/endpoints.ts)、[`src/login.ts`](src/login.ts)、[`src/authenticated-page.ts`](src/authenticated-page.ts)、[`src/browser.ts`](src/browser.ts) |
| Android 登录、密码保存 | [`MainActivity.kt`](android/app/src/main/java/com/classsche/mobile/MainActivity.kt)、[`CredentialStore.kt`](android/app/src/main/java/com/classsche/mobile/CredentialStore.kt)、[`HeadlessLoginClient.kt`](android/app/src/main/java/com/classsche/mobile/HeadlessLoginClient.kt) |
| 错误分类与提示 | [`src/diagnostics.ts`](src/diagnostics.ts)、[`src/authenticated-page.ts`](src/authenticated-page.ts)、[`src/raw-page.ts`](src/raw-page.ts)、[`FailureDetails.kt`](android/app/src/main/java/com/classsche/mobile/FailureDetails.kt)、`MainActivity.kt` |
| 登录提示和页面布局 | [`strings_login.xml`](android/app/src/main/res/values/strings_login.xml)、[`activity_main.xml`](android/app/src/main/res/layout/activity_main.xml) |
| 课表抓取、解析和网页 | [`src/timetable-page.ts`](src/timetable-page.ts)、[`src/html-parser.ts`](src/html-parser.ts)、[`src/timetable-ui.ts`](src/timetable-ui.ts)、[`src/timetable-ui-script.ts`](src/timetable-ui-script.ts) |
| 考试、成绩、等级考试 | `src/` 中对应的 `*-page.ts`、`*-parser.ts`、`*-ui.ts` 和 `fetch-*.ts` |
| 首页和 Android 网页资源 | [`src/home-page-ui.ts`](src/home-page-ui.ts)、[`src/render-ui.ts`](src/render-ui.ts)、[`src/export-android.ts`](src/export-android.ts) |
| Android 原生课表、考试、通知 | `android/app/src/main/java/com/classsche/mobile/` 中的 `Timetable*`、`Exam*`、`CourseNotification*` |

## 仓库根目录

| 文件 | 用途 |
| --- | --- |
| [`README.md`](README.md) | 项目介绍、运行和构建说明。 |
| [`LOGIN-MIGRATION.md`](LOGIN-MIGRATION.md) | 新统一认证入口、页面实测结果和登录态迁移记录。 |
| [`ANDROID.md`](ANDROID.md)、[`API.md`](API.md) | Android 和接口相关说明。 |
| [`AGENT.md`](AGENT.md) | 维护约定，尤其是渲染后再导出 Android 资源的执行顺序。 |
| [`package.json`](package.json)、[`package-lock.json`](package-lock.json) | Node 依赖和 npm 脚本。 |
| [`tsconfig.json`](tsconfig.json) | TypeScript 编译配置；排除旧版 `home-ui*`。 |
| [`.env.example`](.env.example) | 环境变量模板；本地 `.env` 含凭据，不要提交。 |
| [`tests/`](tests/) | `auth.test.mjs` 测登录/跳转，`diagnostics.test.mjs` 测错误分类，`cet-statistics.test.mjs` 测等级考试统计。 |
| [`resources/`](resources/)、[`icon/`](icon/) | 校园地图、校历、图标等原始素材。 |

## `src/`：抓取、解析、网页生成

| 文件 | 用途 |
| --- | --- |
| [`index.ts`](src/index.ts) | 完整抓取入口。 |
| [`config.ts`](src/config.ts)、[`types.ts`](src/types.ts) | 环境配置、路径和共享数据类型。 |
| [`endpoints.ts`](src/endpoints.ts)、[`browser.ts`](src/browser.ts)、[`login.ts`](src/login.ts)、[`authenticated-page.ts`](src/authenticated-page.ts) | 站点地址、Playwright 会话、认证及业务页有效性检查。 |
| [`diagnostics.ts`](src/diagnostics.ts)、[`raw-page.ts`](src/raw-page.ts) | 桌面端步骤、网络、HTTP 和跳转错误分类；成绩类页面原始 HTML 获取。 |
| [`timetable-page.ts`](src/timetable-page.ts)、[`exam-page.ts`](src/exam-page.ts)、[`score-page.ts`](src/score-page.ts)、[`level-exam-page.ts`](src/level-exam-page.ts) | 各业务页面的导航与抓取。 |
| [`html-parser.ts`](src/html-parser.ts)、[`exam-parser.ts`](src/exam-parser.ts)、[`score-parser.ts`](src/score-parser.ts)、[`level-exam-parser.ts`](src/level-exam-parser.ts) | 从页面提取课表、考试、成绩及等级考试数据。 |
| [`fetch-exams.ts`](src/fetch-exams.ts)、[`fetch-scores.ts`](src/fetch-scores.ts)、[`fetch-level-exams.ts`](src/fetch-level-exams.ts)、[`parse-html.ts`](src/parse-html.ts) | 单项抓取及离线重解析入口。 |
| [`home-page-ui.ts`](src/home-page-ui.ts)、[`home-page-ui-script.ts`](src/home-page-ui-script.ts) | 当前首页 HTML 和交互脚本。 |
| [`timetable-ui.ts`](src/timetable-ui.ts)、[`timetable-ui-script.ts`](src/timetable-ui-script.ts) | 课表页面与交互。 |
| [`exam-ui.ts`](src/exam-ui.ts)、[`score-ui.ts`](src/score-ui.ts)、[`level-exam-ui.ts`](src/level-exam-ui.ts) | 考试、成绩和等级考试页面。 |
| [`render-ui.ts`](src/render-ui.ts)、[`export-android.ts`](src/export-android.ts) | 根据本地数据生成网页，再导出到 Android assets；按此顺序运行。 |
| [`fs-utils.ts`](src/fs-utils.ts)、[`logger.ts`](src/logger.ts) | 文件与日志辅助函数。 |
| [`home-ui.ts`](src/home-ui.ts)、[`home-ui-script.ts`](src/home-ui-script.ts) | 旧版首页实现，当前编译和主流程不使用。 |
| [`README.md`](src/README.md) | `src/` 中的详细说明和命令。 |

## `android/`：原生应用

Kotlin 源码位于 [`android/app/src/main/java/com/classsche/mobile/`](android/app/src/main/java/com/classsche/mobile/)，下表文件名均相对于该目录。

| 文件或文件组 | 用途 |
| --- | --- |
| `MainActivity.kt`、`UniversityEndpoints.kt`、`ResourceUpdateStore.kt` | 主界面、站点地址和资源版本记录。 |
| `CredentialStore.kt`、`HeadlessLoginClient.kt` | Android Keystore 密码加密、旧明文迁移和后台认证请求。 |
| `FailureDetails.kt` | Android 超时、域名、安全连接、HTTP、跳转及解析错误的提示分类。 |
| `HeadlessScoreSyncManager.kt`、`HeadlessScoreSyncScheduler.kt`、`HeadlessScoreSyncReceiver.kt` | 成绩后台同步及定时触发。 |
| `ScoreSyncSettings.kt`、`ScoreSyncSettingsActivity.kt`、`ScoreEditorActivity.kt`、`LocalScoreEditorActivity.kt` | 成绩同步设置与本地成绩编辑。 |
| `TimetableCourse.kt`、`TimetableParser.kt`、`TimetableRenderer.kt`、`TimetableScheduleHelper.kt` | 课表模型、解析、原生显示和时间计算。 |
| `TimetableSemesterStore.kt`、`TimetableSemesterSettingsActivity.kt` | 学期选择的保存及设置页面。 |
| `ExamArrangement.kt`、`ExamParser.kt`、`ExamRenderer.kt` | 考试模型、解析和原生显示。 |
| `CourseNotification*`、`ExamNotification*`、`ExamOngoingNotificationScheduler.kt`、`ExamForegroundNotificationService.kt` | 上课及考试通知、闹钟、广播接收和服务。 |
| `NotificationSettingsActivity.kt` | 通知设置界面。 |
| `AppDebugLog.kt`、`LogViewerActivity.kt` | 应用日志和查看界面。 |
| `HomeImagePagerAdapter.kt` | 首页轮播适配器占位文件。 |
| [`README.md`](android/app/src/main/java/com/classsche/mobile/README.md) | Android 源码的详细说明。 |

Android 工程配置在 [`android/app/build.gradle.kts`](android/app/build.gradle.kts)、[`android/build.gradle.kts`](android/build.gradle.kts)、[`android/settings.gradle.kts`](android/settings.gradle.kts)；组件与权限声明在 [`AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml)。布局位于 [`res/layout/`](android/app/src/main/res/layout/)，文案位于 [`res/values/`](android/app/src/main/res/values/)，网络安全和文件共享配置位于 [`res/xml/`](android/app/src/main/res/xml/)。

## 生成内容与本地文件

- `artifacts/`：抓取 HTML、解析 JSON、渲染网页和 Playwright 登录态；由程序生成并被 Git 忽略。
- `android/app/src/main/assets/`：导入应用的网页、JSON 和图片；其中生成的 HTML/JSON/资源目录被 Git 忽略。
- `dist/`、`android/app/build/`：TypeScript 和 Android 构建产物；APK 位于 `android/app/build/outputs/apk/`，均不提交。
- `.env`：本机账号配置，尤其是密码；不要加入索引内容或提交到仓库。

## 错误定位

抓取链路的报错会带具体步骤和目标地址。`网页连接超时` / `无法解析域名` 查网络与站点可用性；`HTTP 状态码` 查目标网站响应；`目标网站跳转错误` 查 SSO 与业务页入口；`页面解析错误` 查页面表格/选择器是否变化。Android 登录 WebView 对主页面网络错误和 HTTP 错误显示代码；课表更新后考试、成绩、等级考试的失败原因分别显示，已有缓存不会因为网页结构错误而被空数据覆盖。详细日志可在 Android 的“运行日志”查看。

常用检查：`npm run check`、`node --test tests/*.test.mjs`；改网页后依次运行 `npm run render:ui` 和 `npm run export:android`；改 Android 后在 `android/` 下运行 `.\gradlew.bat :app:assembleDebug`。
