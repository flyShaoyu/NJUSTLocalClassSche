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
| Android 登录、Cookie 复用、密码保存 | [`MainActivity.kt`](android/app/src/main/java/com/classsche/mobile/MainActivity.kt)、[`LoginNavigationPolicy.kt`](android/app/src/main/java/com/classsche/mobile/LoginNavigationPolicy.kt)（密码/扫码共用门户 `/default/index.html` 等落点到教务 SSO 的跳转与去重）、[`CredentialStore.kt`](android/app/src/main/java/com/classsche/mobile/CredentialStore.kt)、[`HeadlessLoginClient.kt`](android/app/src/main/java/com/classsche/mobile/HeadlessLoginClient.kt)、[`LoginCookieStore.kt`](android/app/src/main/java/com/classsche/mobile/LoginCookieStore.kt) |
| 自动成绩同步过于频繁、认证失败后重试 | [`HeadlessScoreSyncManager.kt`](android/app/src/main/java/com/classsche/mobile/HeadlessScoreSyncManager.kt) 和 [`ScoreSyncTriggerPolicy.kt`](android/app/src/main/java/com/classsche/mobile/ScoreSyncTriggerPolicy.kt)：每个进程首次打开主界面同步一次，返回前台不重复；[`ScoreSyncSettings.kt`](android/app/src/main/java/com/classsche/mobile/ScoreSyncSettings.kt) 和 [`ScoreSyncTiming.kt`](android/app/src/main/java/com/classsche/mobile/ScoreSyncTiming.kt)：后台闹钟的持久间隔及认证暂停 |
| 微信扫码、二维码保存、手动图形验证码 | [`IdentityQrLoginController.kt`](android/app/src/main/java/com/classsche/mobile/IdentityQrLoginController.kt) 和 [`IdentityQrPageScript.kt`](android/app/src/main/java/com/classsche/mobile/IdentityQrPageScript.kt)：首次加载即电脑端 UA、读取网页现有二维码、复用校方 `refresh()` 与分步骤诊断；[`QrImageGalleryStore.kt`](android/app/src/main/java/com/classsche/mobile/QrImageGalleryStore.kt) 的 MediaStore 相册写入；`MainActivity.kt` 的 `showLoginPage` 只重载表单、`authLoginRequested` 手动认证状态、`syncIdentityCaptcha`、`prepareIdentityLogin`、`openTimetableRefreshCaptcha` |
| Android 静默更新课表、切换学期 | [`MainActivity.kt`](android/app/src/main/java/com/classsche/mobile/MainActivity.kt) 的 `startSilentTimetableRefresh`、[`HeadlessLoginClient.kt`](android/app/src/main/java/com/classsche/mobile/HeadlessLoginClient.kt) 的学期表单提交、[`TimetableSemesterStore.kt`](android/app/src/main/java/com/classsche/mobile/TimetableSemesterStore.kt) |
| 错误分类与提示 | [`src/diagnostics.ts`](src/diagnostics.ts)、[`src/authenticated-page.ts`](src/authenticated-page.ts)、[`src/raw-page.ts`](src/raw-page.ts)、[`FailureDetails.kt`](android/app/src/main/java/com/classsche/mobile/FailureDetails.kt)、`MainActivity.kt` |
| 登录提示和页面布局 | [`strings_login.xml`](android/app/src/main/res/values/strings_login.xml)、[`activity_main.xml`](android/app/src/main/res/layout/activity_main.xml)、[`themes.xml`](android/app/src/main/res/values/themes.xml)、[`colors.xml`](android/app/src/main/res/values/colors.xml) |
| Android 应用版本检查与结果缓存 | [`MainActivity.kt`](android/app/src/main/java/com/classsche/mobile/MainActivity.kt) 的 `checkForAppUpdate`、`showLatestVersionDialog` 与 `PREF_UPDATE_*` |
| 本机自动发布 GitHub/Gitee Release | `release-tools/`（整个目录被 Git 忽略，不随仓库分发）：`release.mjs`、`release-lib.mjs`、`release.config.json`、`.env.release`、`release-notes.md` 和 `README.md`；输出及续传记录在 `output/` |
| Android 设置二级页视觉样式 | [`themes.xml`](android/app/src/main/res/values/themes.xml)、[`AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml)、[`res/layout/`](android/app/src/main/res/layout/) 中的 `activity_*settings.xml`、`activity_log_viewer.xml` 和 `activity_score_editor.xml` |
| 课表抓取、解析和网页 | [`src/timetable-page.ts`](src/timetable-page.ts)、[`src/html-parser.ts`](src/html-parser.ts)、[`src/timetable-ui.ts`](src/timetable-ui.ts)、[`src/timetable-ui-script.ts`](src/timetable-ui-script.ts) |
| 考试、成绩、等级考试 | `src/` 中对应的 `*-page.ts`、`*-parser.ts`、`*-ui.ts` 和 `fetch-*.ts` |
| 首页和 Android 网页资源 | [`src/home-page-ui.ts`](src/home-page-ui.ts)、[`src/render-ui.ts`](src/render-ui.ts)、[`src/export-android.ts`](src/export-android.ts) |
| 首页各功能无 UI 验证、空教室与图书馆数据 | [`src/verify-home.ts`](src/verify-home.ts)、[`src/empty-room.ts`](src/empty-room.ts)、[`src/library-data.ts`](src/library-data.ts)、[`src/local-courses.ts`](src/local-courses.ts)、[`src/common-sites.ts`](src/common-sites.ts)；`npm run verify:home`，状态和限制见 [`FEATURE-AUDIT.md`](FEATURE-AUDIT.md) |
| 首页“小工具”合并入口、日程、课程搜索与教师课表 | [`src/campus-tools-ui.ts`](src/campus-tools-ui.ts) 的 `showToolbox` 显示课程搜索与课程评教，`CampusToolsActivity.Bridge.openTool` 打开独立子页并保持返回；首页入口在 `MainActivity.HOME_MENU_ITEMS` / `src/home-page-ui*`，图标为 `ic_home_toolbox.xml`。查询见 `CampusDataClient.kt` / `CampusDataParser.kt`，日程见 `LocalScheduleStore.kt` / `Timetable*`。 |
| 选课按钮、课程分类搜索与筛选 | [`src/enrollment-catalog-ui.ts`](src/enrollment-catalog-ui.ts) 提供 `showEnrollment` / 去重搜索筛选与分页，复用 `general-education-catalog.ts`；`CampusToolsActivity.kt` 注入 `GeneralEducationCatalogStore` 同一手机缓存，本机更正与成绩页共享。`CourseEnrollmentFeature.kt` 浏览已开放、提交仍关闭；只有本地目录，没有真实选退课 HTTP 或抢课任务。验证见 `tests/enrollment-catalog.test.mjs`。 |
| 新页面加载、顶栏高度、Android 兼容回归 | `CampusToolsActivity.kt` 的本地 history URL / `CampusUI.start()` 握手、`SecondaryPageToolbar.kt`（主/副标题居中）、`MainActivity.applyScoreSectionToolbar` / `classsche://score-section/`（学分子页标题/操作）、`GeneralEducationUpdateActivity.kt` 的 DOM 回调超时、`GeneralEducationCatalogStore.kt` 的 Android ICU 正则；设备验证在 `android/app/src/androidTest/java/com/classsche/mobile/RuntimeChecks.kt`，命令见 `ANDROID.md`。 |
| 后台同步 Cookie 写入失败、等待或无结果 | `LoginCookiePolicy.kt` 忽略无效 `Domain=edu.cn`，`LoginCookieStore.kt` 保存有效原始 Cookie，`HeadlessLoginClient.kt` 控制认证锁及总时限；`MainActivity.kt` 的独立课表刷新队列和 `presentRefreshResult` 显示结果。 |
| 课程评教、评级预览、一键提交与防重复 | `src/campus-tools-ui.ts` 的 `showEvaluation`；`TeachingEvaluationParser.kt`（列表/表单/逐题选项）、`TeachingEvaluationEngine.kt`（只读预览、逐门提交、校方状态与中断）、`TeachingEvaluationClient.kt`（现有 Cookie、受限 HTTP、私有提交记录）、`TeachingEvaluationJson.kt`；桥接与原生确认在 `CampusToolsActivity.kt`，首页入口在 `MainActivity.kt`。验证见 `tests/evaluation.test.mjs`、`tests/android/EvaluationCheck.java` 及设备 `phase=evaluation`；测试不提交真实评教。 |
| 教务处公开网页返回防护页、更新通识课程目录 | [`src/public-site-browser.ts`](src/public-site-browser.ts)（普通 Edge/Chrome 独立会话）、[`src/general-education-source.ts`](src/general-education-source.ts)（正文验证、解析、原子更新）、[`src/fetch-general-education.ts`](src/fetch-general-education.ts)（命令）。`npm run fetch:general-education`，失败支持 `--file`。 |
| 手机更新通识课程分类表、校方 WebView 校验 | `GeneralEducationUpdateActivity.kt`、`GeneralEducationPageScript.kt`（前台等待与目标 DOM 读取）、`GeneralEducationCatalogParser.kt` / `GeneralEducationCatalogStore.kt`（校验、原子缓存及页面注入），入口和结果回调在 `MainActivity.kt`。 |
| 成绩页通识选修分类学分 | [`src/general-education-ui.ts`](src/general-education-ui.ts)（计算，含计划外/手动辅修）及 [`src/general-education-pages.ts`](src/general-education-pages.ts)（按类别学分、分栏编辑草稿、可搜索分类表与逐级返回），[`src/general-education-drag.ts`](src/general-education-drag.ts)（长按/手柄跨栏移动、边缘滚动、取消；记录由页面统一保存）、[`src/general-education-catalog.ts`](src/general-education-catalog.ts)（742 行公共课程表；无需全文读取）、[`src/score-ui.ts`](src/score-ui.ts)（入口），Android 集成见 `MainActivity.loadScorePageWithLatestData/leaveScorePage`。 |
| Android 原生课表、考试、通知 | `android/app/src/main/java/com/classsche/mobile/` 中的 `Timetable*`、`Exam*`、`CourseNotification*` |

## 仓库根目录

| 文件 | 用途 |
| --- | --- |
| [`README.md`](README.md) | 项目介绍、运行和构建说明。 |
| [`CHANGELOG.md`](CHANGELOG.md) | 未发布改动的简要记录。 |
| [`LOGIN-MIGRATION.md`](LOGIN-MIGRATION.md) | 新统一认证入口、页面实测结果和登录态迁移记录。 |
| [`FEATURE-AUDIT.md`](FEATURE-AUDIT.md) | 2026-10-28 功能盘点：首页未实现入口、教务页面实际核查、可扩展功能、数据限制及接入顺序。 |
| [`ANDROID.md`](ANDROID.md)、[`API.md`](API.md) | Android 和接口相关说明。 |
| `release-tools/` | 本机可选发布工具的独立目录，包含脚本、配置、Token、简介、说明、测试和发布产物；整个目录被忽略。根目录 `npm run release` 指向这里，克隆仓库本身不会获得本机工具。 |
| [`AGENT.md`](AGENT.md) | 维护约定，尤其是渲染后再导出 Android 资源的执行顺序。 |
| [`package.json`](package.json)、[`package-lock.json`](package-lock.json) | Node 依赖和 npm 脚本。 |
| [`tsconfig.json`](tsconfig.json) | TypeScript 编译配置；排除旧版 `home-ui*`。 |
| [`.env.example`](.env.example) | 环境变量模板；本地 `.env` 含凭据，不要提交。 |
| [`tests/`](tests/) | `auth.test.mjs` 测登录/跳转，`diagnostics.test.mjs` 测错误分类，`cet-statistics.test.mjs` 测等级考试统计。 |
| [`tests/android-login.test.mjs`](tests/android-login.test.mjs)、[`tests/android/`](tests/android/) | 用编译后的 Kotlin 类离线模拟 Cookie 复用、SSO、过期后单次密码提交、错误不重登、并发及同步间隔；先构建 Android，需 JDK 与本机 Gradle 依赖缓存。 |
| [`tests/identity-qr.test.mjs`](tests/identity-qr.test.mjs) | 在离线模拟页面执行 Android 实际二维码脚本，验证现有图片复用、过期刷新、前台恢复状态检查、确认后单次 POST 与 Cookie/隐藏字段保留、错误及恢复；不访问学校，不截屏。 |
| [`tests/home-data.test.mjs`](tests/home-data.test.mjs) | 空教室分组列与状态、教学楼备用接口、手动课程持久化、图书检索与馆藏、借阅空数据和 SSO 的离线测试，不使用真实个人数据。 |
| [`tests/campus-ui.test.mjs`](tests/campus-ui.test.mjs)、[`tests/android-campus.test.mjs`](tests/android-campus.test.mjs) | 新校园页面交互、自由时间日程、错误恢复与 Kotlin 查询解析测试；不截屏。 |
| [`tests/general-education-source.test.mjs`](tests/general-education-source.test.mjs) | 防护页与正常含脚本正文的区分、失败不覆盖、直接读 DOM 不重复请求。 |
| [`tests/android-general-education-page.test.mjs`](tests/android-general-education-page.test.mjs) | 执行 Android 实际 DOM 读取脚本，验证等待、防护/错误识别、完整字段和不重复请求；原生解析另见 `tests/android-campus.test.mjs`。 |
| [`tests/general-education.test.mjs`](tests/general-education.test.mjs) | 通识选修学分统计、课程表完整性、重修去重、分类冲突、页面入口与本机保存测试。 |
| [`resources/`](resources/)、[`icon/`](icon/) | 校园地图、校历、图标等原始素材。 |

## `src/`：抓取、解析、网页生成

| 文件 | 用途 |
| --- | --- |
| [`index.ts`](src/index.ts) | 完整抓取入口。 |
| [`verify-home.ts`](src/verify-home.ts) | 首页十项无 UI 验证入口，保留原缓存；结果和登录态写入被忽略的 `artifacts/home-data/`。 |
| [`empty-room.ts`](src/empty-room.ts) | 空教室筛选、校方教学楼备用接口及跨星期／节次结果解析。 |
| [`library-data.ts`](src/library-data.ts) | 公开馆藏检索、分页、馆藏状态、按当前OPAC表单查询、复用图书馆会话与当前借阅，只读，不自动提交密码。 |
| [`local-courses.ts`](src/local-courses.ts)、[`common-sites.ts`](src/common-sites.ts) | 桌面验证用手动课程独立存储；学校网站清单也供校园工具页面使用。Android 日程使用独立的 `LocalScheduleStore.kt`。 |
| [`config.ts`](src/config.ts)、[`types.ts`](src/types.ts) | 环境配置、路径和共享数据类型。 |
| [`endpoints.ts`](src/endpoints.ts)、[`browser.ts`](src/browser.ts)、[`login.ts`](src/login.ts)、[`authenticated-page.ts`](src/authenticated-page.ts) | 站点地址、Playwright 会话、认证及业务页有效性检查。 |
| [`diagnostics.ts`](src/diagnostics.ts)、[`raw-page.ts`](src/raw-page.ts) | 桌面端步骤、网络、HTTP 和跳转错误分类；成绩类页面原始 HTML 获取。 |
| [`timetable-page.ts`](src/timetable-page.ts)、[`exam-page.ts`](src/exam-page.ts)、[`score-page.ts`](src/score-page.ts)、[`level-exam-page.ts`](src/level-exam-page.ts) | 各业务页面的导航与抓取。 |
| [`html-parser.ts`](src/html-parser.ts)、[`exam-parser.ts`](src/exam-parser.ts)、[`score-parser.ts`](src/score-parser.ts)、[`level-exam-parser.ts`](src/level-exam-parser.ts) | 从页面提取课表、考试、成绩及等级考试数据。 |
| [`fetch-exams.ts`](src/fetch-exams.ts)、[`fetch-scores.ts`](src/fetch-scores.ts)、[`fetch-level-exams.ts`](src/fetch-level-exams.ts)、[`parse-html.ts`](src/parse-html.ts) | 单项抓取及离线重解析入口。 |
| [`home-page-ui.ts`](src/home-page-ui.ts)、[`home-page-ui-script.ts`](src/home-page-ui-script.ts) | 当前首页 HTML 和交互脚本。 |
| [`campus-tools-ui.ts`](src/campus-tools-ui.ts) | Android 本地校园工具页面与交互：日程、教室、网站、图书馆、课程/教师搜索；由导出生成空数据模板。 |
| [`timetable-ui.ts`](src/timetable-ui.ts)、[`timetable-ui-script.ts`](src/timetable-ui-script.ts) | 课表页面与交互。 |
| [`public-site-browser.ts`](src/public-site-browser.ts)、[`general-education-source.ts`](src/general-education-source.ts)、[`fetch-general-education.ts`](src/fetch-general-education.ts) | 桌面教务处公开分类表的普通浏览器读取、正文验证、本地网页导入及显式目录更新。独立配置目录在被忽略的 `artifacts/jwc-source/`。 |
| [`general-education-ui.ts`](src/general-education-ui.ts)、[`general-education-catalog.ts`](src/general-education-catalog.ts) | 成绩页通识选修分类学分统计及校方公共课程目录；修改规则优先读 UI 文件，目录仅按课程号定向搜索。 |
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
| `LoginCookieStore.kt` | 后台响应 Cookie 按原域名/路径写回 WebView，等待异步完成并持久保存；允许后续同步复用和同步删除过期 Cookie。 |
| `ScoreSyncTiming.kt`、`ScoreSyncTriggerPolicy.kt` | 后台定时间隔计算与进程级启动同步门控：首次打开主界面忽略定时间隔，切回或重建界面不重复；时间戳和认证暂停由 `ScoreSyncSettings.kt` 持久保存。 |
| `IdentityQrLoginController.kt`、`IdentityQrPageScript.kt` | 电脑端认证页、读取网页现有二维码、复用校方刷新入口；原生驱动状态检查及从微信返回后的立即检查；区分状态 `0/2/1/3`，确认后单次提交真实表单、进度和超时。`MainActivity.continueLoginNavigation` 与 `LoginNavigationPolicy.kt` 统一密码/扫码后门户到教务的跳转。图形验证码在 `MainActivity.kt` 手动填写，无 OCR 依赖。 |
| `LoginNavigationPolicy.kt` | 识别门户 `/index.html`、`/default/index.html`、`/default/index` 和教务主页，明确用户发起登录后才继续，跨页面回调去重；不直接判定认证或课表抓取成功。 |
| `QrImageGalleryStore.kt` | 把用户点击保存的当前二维码 PNG 写入相册“周三课表”，兼容 Android 8/9 写入权限与 Android 10+ MediaStore，失败清理残留图片。 |
| `FailureDetails.kt` | Android 超时、域名、安全连接、HTTP、跳转及解析错误的提示分类。 |
| `HeadlessScoreSyncManager.kt`、`HeadlessScoreSyncScheduler.kt`、`HeadlessScoreSyncReceiver.kt` | 成绩后台同步及定时触发。 |
| `ScoreSyncSettings.kt`、`ScoreSyncSettingsActivity.kt`、`ScoreEditorActivity.kt`、`LocalScoreEditorActivity.kt` | 成绩同步设置与本地成绩编辑。 |
| `CampusToolsActivity.kt`、`CampusBrowserActivity.kt` | 本地工具桥接、查询状态与独立远程网站 WebView。 |
| `CampusDataClient.kt`、`CampusDataParser.kt` | 校园只读 HTTP 查询与 Jsoup 解析；复用 Cookie，不提交密码。 |
| `GeneralEducationUpdateActivity.kt`、`GeneralEducationPageScript.kt` | 手机普通 WebView 更新通识分类表、同文章地址校验、加载/校验/保存状态和前台超时，不暴露远程桥接。 |
| `GeneralEducationCatalogParser.kt`、`GeneralEducationCatalogStore.kt` | 标题、表头和逐行验证；AtomicFile 私有目录缓存、完整保留冲突与向成绩模板注入。 |
| `LocalScheduleStore.kt` | 本地日程原子保存、周次/自由时间验证、可选地点与备注、按学期合并为个人课表显示数据。 |
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

抓取链路的报错会带具体步骤和目标地址。`网页连接超时` / `无法解析域名` 查网络与站点可用性；`HTTP 状态码` 查目标网站响应；`目标网站跳转错误` 查 SSO 与业务页入口；`页面解析错误` 查页面表格/选择器是否变化。Android 登录 WebView 对主页面网络错误和 HTTP 错误显示代码；统一认证的登录失败页还会读取表单错误原文，不能把“表单已点击”视为认证成功。微信扫码使用电脑端 User-Agent 获取校方二维码图片，仍沿用同一 WebView 会话。图形验证码从网页当前图片读取，用户手动填写或刷新；静默课表更新需要验证码时自动进入登录页，并保留后台认证 Cookie，提交后继续更新。课表更新后考试、成绩、等级考试的失败原因分别显示，已有缓存不会因为网页结构错误而被空数据覆盖。详细日志可在 Android 的“运行日志”查看。

常用检查：`npm run check`、`node --test tests/*.test.mjs`；改网页后依次运行 `npm run render:ui` 和 `npm run export:android`；改 Android 后在 `android/` 下运行 `.\gradlew.bat :app:assembleDebug`。

Android 登录改动还应在构建后运行 `node --test tests/android-login.test.mjs`。该测试只使用本机模拟响应；没有 JDK 或 Android 编译产物时会跳过，不影响纯桌面端检查。


## 本轮页面及考试改动入口（2026-10-30）

- `src/general-education-pages.ts`：统计原页编辑、移至待分类、下拉分类、贴边搜索、总学分及黄色目录入口；拖动机制复用 `src/general-education-drag.ts`。
- `src/campus-tools-ui.ts`：周次全选、图书搜索置顶及错误呈现。
- `android/app/src/main/java/com/classsche/mobile/ExamTimetableOverlay.kt`：考试映射到课表，未知校历不猜测。由 `TimetableRenderer.kt` 显式开启，`MainActivity.kt` 只在本人课表启用。
- `CampusDataClient.kt` / `CampusDataParser.kt`、`src/library-data.ts`：新版OPAC参数、借阅登录与书目编码；`CampusToolsActivity.kt` 路由图书馆登录。
- `CampusBrowserActivity.kt`：当前链接复制、外部网页缩放；`GeneralEducationUpdateActivity.kt` 与主页面远程 WebView 同步开启缩放。
- `tests/general-education-drag.test.mjs`、`tests/campus-ui.test.mjs`、`tests/home-data.test.mjs`；原生 `RuntimeChecks.kt` 的 `refinement` / `libraryNew` 为本轮验收入口。详细结果在被忽略的 `artifacts/ui-refinement-20261030/`。


本轮多时段修改：`src/campus-tools-ui.ts`负责大节多选、位置排序与搜索布局；`CampusDataClient.kt`验证分段参数及学校表单，`CampusDataParser.intersectFreeRooms`按编号取全部空闲交集。回归在`tests/campus-ui.test.mjs`和`tests/android/CampusParserCheck.java`，模拟器新增`roomsMulti`阶段。证据目录：`artifacts/ui-refinement-followup-20261030/`。


详情与首页整理入口（2026-10-30）：`LocalScheduleStore.kt`的`formatWeeks`及`src/campus-tools-ui.ts`负责区间周次；`TimetableCourse.kt`/`TimetableRenderer.kt`/`ExamTimetableOverlay.kt`传递可选座位号，`src/timetable-ui.ts`/`timetable-ui-script.ts`控制详情标签及顺序。`MainActivity.applyToolbarLayout`控制首页留白，`src/exam-ui.ts`控制考试默认展开。专项回归见`tests/campus-ui.test.mjs`、`tests/exam-ui.test.mjs`与原生`refinement`/`homeToolbar`/`examExpansion`阶段。证据目录`artifacts/detail-refinement-20261030/`。


图书馆四栏：`CampusDataParser.readerSummary`只解析官网状态容器；`CampusDataClient.readerStatus`只读不缓存；`CampusToolsActivity`路由独立登录/返回刷新；`CampusBrowserActivity`的`library_login/private_library`实现临时表单及原站会话复用；`src/campus-tools-ui.ts`只在我的图书馆标签请求4栏，不写LocalStorage。隐私和结构回归位于`CampusParserCheck.java`、`campus-ui.test.mjs`，原生验收阶段`libraryAccount`使用明确模拟状态值。


原生图书馆登录框：`LibraryLoginActivity.kt`和`activity_library_login.xml`；登录脚本回归`tests/library-login.test.mjs`，设备原生验证码图片显示阶段`libraryNativeForm`（纯模拟图）、真实只读状态/会话复用阶段`libraryReal`（不记录真实值、不截图）。


页面交互整理（2026-10-30）：`src/timetable-colors.ts`维护同周冲突图/Oklab色差选择，`timetable-ui.ts`维护30色及日程按钮；`campus-tools-ui.ts`的`loadBooks`串行合并最新查询、`renderLibraryAccount`管理四栏和底部操作；`enrollment-catalog-ui.ts`两行目录卡片；`general-education-drag.ts`维护触点居中、transform及最终松手坐标。覆盖在`tests/timetable-colors.test.mjs`、`campus-ui.test.mjs`、`general-education-drag.test.mjs`、`enrollment-catalog.test.mjs`；验证证据在被忽略的`artifacts/ui-polish-20261030/`。


本轮跟进：`LibraryCredentialStore.kt`负责图书馆账号/密码/类型的独立Keystore密文与原子保存；`LibraryLoginActivity`只在本轮提交认证成功后保存并在重开时填入。设备阶段`libraryCredentials`为隔离的模拟认证闭环。`general-education-pages.ts`的`resetDefaults`重置分类草稿；课表详情正文与圆点布局在`timetable-ui.ts`，滚轮正文优先在`timetable-ui-script.ts`。专项测试位于`campus-ui.test.mjs`与`general-education-drag.test.mjs`，证据目录`artifacts/ui-polish-followup-20261030/`。


预览发布：本机`release-tools/release-lib.mjs`管理语义版本及预发布API属性，`release.mjs`负责--prerelease计划与续传；测试`release-tools/tests/*.test.mjs`完全模拟外部写入。`ReleaseVersion.kt`用于Android更新后缀提取与比较，回归在`CampusParserCheck.java`。


学校邮箱：`SchoolMailActivity.kt`（原生设置、列表、阅读、写信、回执）、`SchoolMailModels.kt`（模型、输入校验、独立授权码密文）、`SchoolMailClient.kt`（Angus IMAP/SMTP）、`SchoolMailMime.kt`（有界纯文字解析）、`SchoolMailOutbox.kt`（发送唯一编号及未知状态持久化）、`SchoolMailPolling.kt`（默认关闭的系统联网周期任务及隐私通知）。布局`activity_school_mail.xml`，入口在`campus-tools-ui.ts`/`CampusToolsActivity.kt`。核心和UID/MIME/回执测试在`SchoolMailChecks.kt`，设备阶段schoolMail/schoolMailTls/schoolMailReal/schoolMailSendTest在`RuntimeChecks.kt`；证据目录`artifacts/school-mail-20261030/`。
