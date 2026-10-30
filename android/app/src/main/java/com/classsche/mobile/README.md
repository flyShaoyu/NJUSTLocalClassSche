# `com.classsche.mobile` 源码说明

Android 原生代码负责 WebView 登录、应用私有缓存、首页和业务页面、通知、后台成绩同步以及版本/资源更新。更完整的流程见仓库根目录 [ANDROID.md](../../../../../../../../ANDROID.md) 与 [FILE-INDEX.md](../../../../../../../../FILE-INDEX.md)。

## 登录、缓存与更新

- `MainActivity.kt`：界面和主要业务入口；处理统一认证 WebView、业务页捕获、原生首页、缓存及更新检查。
- `UniversityEndpoints.kt`：统一认证、教务 SSO、课表、考试和成绩 URL。
- `CourseEnrollmentFeature.kt`：本地课程分类目录可浏览，选课/抢课提交继续关闭。目录与深链可进入，`CampusToolsActivity` 注入成绩页同一分类缓存；搜索和筛选由 `src/enrollment-catalog-ui.ts` 本地执行。教务选课中心 URL 仅作元信息；选课业务桥接仍被拦截，没有网络选退课或余量轮询。
- `TeachingEvaluationParser.kt` / `TeachingEvaluationEngine.kt` / `TeachingEvaluationClient.kt` / `TeachingEvaluationJson.kt`：课程评教。Cookie 复用、GET 预览、逐题评级与可选评语、原生确认后串行 POST、校方状态核对和落盘防重复；批次脚本另需学校网页核对。测试使用模拟服务，不替真实账号提交。
- `LoginCookiePolicy.kt`：保留原站点/大学父域 Cookie，过滤校方 `Domain=edu.cn` 无效 Cookie；`LoginCookieStore.kt` 将有效 Cookie 写回 WebView，失败只记录域名和 Cookie 名称。`HeadlessLoginClient.kt` 为认证锁等待和网络总流程设置时限。
- `SecondaryPageToolbar.kt`：校园工具、内置浏览器和分类更新页共用现有设置页的紧凑顶栏尺寸。`CampusToolsActivity.kt` 使用固定本地 history URL 和页面完成握手启动前端；设备回归入口在 `app/src/androidTest/java/com/classsche/mobile/RuntimeChecks.kt`。
- `CredentialStore.kt`：Android Keystore AES-GCM 密码保存和旧明文迁移；使用智慧理工服务门户密码。
- `IdentityQrLoginController.kt`、`IdentityQrPageScript.kt`：首次加载认证表单就使用电脑端 User-Agent；弹窗直接读取网页当前二维码，不重载整页或再次下载图片。过期后“重新获取”复用校方 `refresh()`，刷新期间禁止重复申请及保存旧码；申请、图片加载和状态轮询分别显示超时、HTTP 或解析错误。准备密码表单暂停扫码，用户点击扫码后才恢复；保存按钮使用当前图片，不中断轮询。
- 校方状态 `0/2/1/3` 分别表示等待扫码/已扫码待确认/微信已确认/失效。`IdentityQrLoginController.onHostResumed` 返回前台立即查原二维码，原生 Handler 每秒驱动同会话 `isUsed()` 并限制一个在途请求，不依赖隐藏 WebView 的定时器。收到 `1` 后单次提交当前二维码表单，保留 Cookie、service 和隐藏字段；`MainActivity.continueLoginNavigation` 在门户/教务主页可用后继续 SSO，不等门户资源全部加载。弹窗与 `QR_AUTH` 日志显示确认、提交、教务会话和课表加载步骤，单步 20 秒或总流程 60 秒超时明确报错，排除用户切后台的时间。
- `LoginNavigationPolicy.kt`：密码和扫码共用门户落点识别，支持 `/index.html`、`/default/index.html`、`/default/index`，沿用 WebView Cookie 进入教务 SSO 及课表；仅用户明确发起认证后触发，每次认证分别去重两个跳转阶段。`AUTH_FLOW` 记录中转步骤，不包含查询参数、票据或 Cookie；需要有效课表后才解除认证暂停并更新缓存。
- `QrImageGalleryStore.kt`：将二维码 PNG 保存到相册“周三课表”，使用 MediaStore 并清理保存失败时的残留；Android 8/9 的写入权限回调由 `MainActivity.kt` 管理。
- `MainActivity.kt` 的 `showLoginPage`：进入登录界面时只重载 `UniversityEndpoints.LOGIN_FORM`，保留 Cookie，禁止自动 SSO 跳转和课表捕获；`authLoginRequested` 由提交登录或扫码按钮开启。验证码交接在恢复后台会话后准备表单，等待用户填写并提交。
- `MainActivity.kt` 的 `syncIdentityCaptcha`：读取当前认证会话的验证码图片，手动填写或刷新，不使用 OCR；`openTimetableRefreshCaptcha`：静默更新遇到验证码时转登录页，保留后台 Cookie，提交后继续课表更新。
- `HeadlessLoginClient.kt`：纯 HTTP 认证与教务会话获取，提交前检查验证码要求，必要时通过 `CaptchaRequiredException` 交接会话；`HeadlessScoreSyncManager.kt`、`HeadlessScoreSyncScheduler.kt`、`HeadlessScoreSyncReceiver.kt`：后台成绩同步。
- `LoginCookieStore.kt`：后台原始 `Set-Cookie` 写回 WebView，等待异步完成并落盘；后续先复用教务 Cookie，再尝试 CAS Cookie，均失效时才提交一次密码。网络/解析失败不转为密码登录，认证段串行避免并发重登。
- `ScoreSyncTriggerPolicy.kt`：每个进程首次打开主界面尝试同步一次，忽略后台定时间隔；进程仍存活时切回前台、重新进入或重建界面不重复，后台闹钟仍按间隔运行。关闭同步、仅 Wi-Fi、认证暂停和进行中的同步继续生效。
- `ScoreSyncSettings.kt`、`ScoreSyncTiming.kt`：持久记录最近自动同步尝试时间，计算后台闹钟剩余间隔；启动同步也重新起算定时间隔，默认 2h。认证被拒或要求验证码后暂停自动认证，手动认证成功解除。
- `ResourceUpdateStore.kt`：公共页面资源更新；`FailureDetails.kt`：超时、HTTP、跳转和解析错误提示；`AppDebugLog.kt`、`LogViewerActivity.kt`：运行日志。

## 课表与考试

- `TimetableCourse.kt`、`TimetableParser.kt`、`TimetableRenderer.kt`、`TimetableScheduleHelper.kt`：课程模型、HTML 解析、页面和时间计算。
- `TimetableSemesterStore.kt`、`TimetableSemesterSettingsActivity.kt`：多学期课表设置与存储。
- `ExamArrangement.kt`、`ExamParser.kt`、`ExamRenderer.kt`：考试数据和展示。
- `CourseNotification*`、`ExamNotification*`、`ExamOngoingNotificationScheduler.kt`、`ExamForegroundNotificationService.kt`：上课/考试提醒与常驻通知。
- `NotificationSettingsActivity.kt`：提醒开关、提前量等设置。

## 手机更新通识分类表

- `GeneralEducationUpdateActivity.kt`：分类表页面标题栏手动更新，由正常移动 WebView 完成校方校验；同文章地址约束、状态、45 秒前台超时、重试与陈旧回调隔离。
- `GeneralEducationPageScript.kt`：等待文档解析和目标表就绪，读取标题与目标表 HTML，不重复发请求，不注入原生桥接。
- `GeneralEducationCatalogParser.kt`：纯 Jsoup 标题/表头/逐行校验，保留原分类及重复冲突。
- `GeneralEducationCatalogStore.kt`：应用私有 AtomicFile 缓存，失败/取消保留旧表，成绩模板优先注入本机目录；无缓存回退内置目录。
- `MainActivity.kt`：更新入口去重与结果处理；`src/general-education-ui.ts` 接收新目录后原位重算，保留手动分类。脚本离线测试 `tests/android-general-education-page.test.mjs`；原生解析测试 `tests/android-campus.test.mjs`。

## 校园工具与本地日程

- `CampusToolsActivity.kt`：首页工具入口、本地页面桥接、查询状态和错误提示；`toolbox` 是“小工具”目录，`openTool` 打开课程搜索/课程评教独立子页并保持返回。教师课表复用课表模板并独立渲染，不覆盖个人缓存。
- `CampusDataClient.kt` / `CampusDataParser.kt`：空闲教室、课程/教师搜索、图书及借阅查询；只读，优先已有 Cookie / SSO，登录过期交由用户登录，不提交密码。
- `LocalScheduleStore.kt`：私有文件原子存储日程，周次、星期、自由起止时间、可选地点和备注；显示时合并到个人课表，首页与通知按实际时间计算。编辑入口使用本地课表详情链接，不上传。
- `CampusBrowserActivity.kt`：常用网站的独立 WebView，共享 Cookie，不暴露本地工具桥接。
- `SecondaryPageToolbar.kt` 与所有顶栏布局将标题、副标题一起居中；本地工具正文统一 13px，日程、图书、教师搜索子页通过 `Bridge.setSection` 更新标题。
- 页面源码 `src/campus-tools-ui.ts`，导出为 `campus-tools.html`。对应回归 `tests/campus-ui.test.mjs` 与 `tests/android-campus.test.mjs`，均不截屏。

## 成绩与其他页面

- `ScoreSyncSettings.kt`、`ScoreSyncSettingsActivity.kt`：后台成绩同步设置。
- `MainActivity.loadScorePageWithLatestData/leaveScorePage`：成绩页固定本地来源；`applyScoreSectionToolbar` 随统计/集中分类/分类表切换标题及保存动作，顶栏返回逐级退出。集中分类页支持长按/手柄跨类别拖动，记录沿用原本机分类键统一保存。统计纳入计划外候选并增加手动辅修，具体计算、页面和 742 行公共目录在 `src/general-education-ui.ts` / `src/general-education-pages.ts` / `src/general-education-catalog.ts`，不改变成绩 JSON。
- `ScoreEditorActivity.kt`、`LocalScoreEditorActivity.kt`：本地成绩条目编辑，不回写教务网站。
- `HomeImagePagerAdapter.kt`：保留的轮播适配器占位文件；主要首页逻辑在 `MainActivity.kt`。

Android 个人数据写入应用私有目录；导入 APK 的 `assets` 只含空数据页面模板和公共资源。改网页模板须先在仓库根目录依次运行 `npm run render:ui`、`npm run export:android`，再构建 APK。

登录回归：先构建 `:app:assembleDebug`，再从仓库根目录运行 `node --test tests/android-login.test.mjs`。测试只访问本机模拟响应，不提交真实账号密码。

二维码回归：`node --test tests/identity-qr.test.mjs`，执行实际 Android 页面脚本，使用离线页面检查图片复用、过期刷新、重复表单、错误及恢复，不截屏。
