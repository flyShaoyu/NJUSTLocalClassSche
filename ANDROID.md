# Android 说明

Android 工程位于 `android/`，桌面抓取工程位于 `src/`。应用提供原生首页、登录、课表/考试/成绩/等级考试页面、本地缓存、课程与考试通知、成绩后台同步，以及版本和资源更新检查。

## 登录与本地数据

- 登录入口由 `UniversityEndpoints.kt` 统一定义。应用使用 WebView 完成统一身份认证，再通过教务 SSO 建立教务域名的会话。登录页有提交登录、加载登录页和微信扫码登录入口；`IdentityQrLoginController.kt` 使用电脑端 User-Agent 请求校方二维码图片，在原生弹窗展示，并沿用同一 WebView 会话等待扫码确认和跳转。学校要求图形验证码时，登录页显示当前网页已加载的图片，用户手动填写或刷新；不引入 OCR 库或模型。页面使用与主页相同的背景和卡片宽度、蓝色控件，以及黄色的校方限制提示。
- 登录页使用**智慧理工服务门户密码**。`CredentialStore.kt` 用 Android Keystore 中的不可导出密钥进行 AES-GCM 加密；旧版保存在偏好设置中的明文密码在首次读取时迁移并删除。后台同步需要在未打开界面时解密，因此密钥不要求每次生物识别。
- 每次进入登录界面只重新加载认证表单，保留现有 Cookie，不自动登录或抓取课表。`UniversityEndpoints.LOGIN_FORM` 直接访问统一认证并携带 `renew=true`；`MainActivity.authLoginRequested` 仅由提交登录或扫码按钮开启，在此之前拦截跨站 SSO 跳转并禁止业务页面捕获。二维码弹窗关闭后也回到准备表单的状态。已有登录或同步正在执行时复用当前操作，避免重复触发；课表验证码交接先恢复后台 Cookie，再准备表单，填写并提交后才继续认证和同步。
- 微信扫码弹窗提供“保存到相册”，由 `QrImageGalleryStore.kt` 把当前显示的二维码保存为 PNG 到 `Pictures/周三课表`，保留扫码弹窗和认证轮询。二维码未加载或已过期时不能保存；保存期间禁用重复点击，失败清理未完成图片。Android 10 及以上使用 MediaStore，不申请相册读取权限；Android 8/9 仅在点击保存时申请写入权限，拒绝后提示原因。
- 登录页加载、提交和扫码期间会禁用重复操作；首页“更新数据”（保留原刷新图标）优先复用已有教务会话，必要时使用保存的凭据在后台完成统一认证、所选学期课表抓取及相关缓存同步。通常不打开登录页；校方要求图形验证码时自动跳转到登录页，填写后提交登录继续更新。同步期间显示“更新中…”，重复点击只提示当前操作；失败时保留原课表缓存并显示具体错误。
- `HeadlessLoginClient.kt` 和 `HeadlessScoreSyncManager.kt` 处理纯 HTTP 登录及成绩同步，并遵守 Cookie 的域名/路径范围；课表静默更新复用该登录器，并用返回的教务 Cookie 拉取考试、成绩及等级考试。后台登录先查询学校验证码要求；需要验证码或收到图形动态码错误时通过 `CaptchaRequiredException` 把认证地址和 Cookie 交给登录 WebView，不反复提交。WebView 重新加载认证页后获取当前会话的验证码，手动验证成功后继续抓取课表。
- `LoginCookieStore.kt` 将后台每个响应的原始 `Set-Cookie`（包括过期删除）写回同一 WebView Cookie 存储，等待异步写入完成后才继续，在本轮结束时 `flush()` 落盘。重新加载登录表单不再删除已有会话。下一次先验证课表内容；教务 Cookie 有效就跳过认证，失效才尝试 CAS Cookie 恢复 SSO，仍出现真实认证表单时才提交一次密码。超时、HTTP 5xx、异常跳转和页面结构错误不触发密码回退。不同后台入口的认证段串行执行，后进入的任务可复用前一任务更新后的 Cookie。`HEADLESS_SESSION` 日志注明复用结果，不打印 Cookie 或票据。
- 自动成绩同步由 `HeadlessScoreSyncManager` 中的进程级 `ScoreSyncTriggerPolicy` 控制：进程启动后首次打开主界面额外尝试一次，不受后台定时间隔限制；进程仍存活时切回前台、重新进入或重建主界面都不重复。首次触发因设置、认证暂停或已有同步而跳过时也不在切回前台后补试。进程被结束后重新打开可再次触发。后台闹钟继续按设置间隔执行（默认 2h，可选 1h、2h、5h、12h、24h）；`ScoreSyncSettings` 持久保存最近尝试时间，`ScoreSyncTiming` 计算剩余时间，启动同步也从本次尝试起重新计算下次闹钟。关闭同步、仅 Wi-Fi 和认证失败暂停同样约束启动同步；账号冻结、密码被拒或需要验证码后暂停自动认证，手动登录或手动更新课表成功后解除暂停。
- 个人课表、考试和成绩缓存写入应用私有目录，升级 APK 时保留。应用关闭系统备份；不要把密码、Cookie、票据或用户数据提交到仓库。
- “检查更新”将有更新和无更新结果都缓存 24 小时；有更新时连同版本、发布页、下载地址和简介一起保存，再次点击直接显示结果。弹窗中的“重新获取更新”会绕过缓存并重新请求发布页。新版本号仍用于设置页红点。
- 安装包使用系统 `DownloadManager` 下载；切到后台或主界面重建后任务仍由系统继续，返回应用时按持久化的任务 ID 恢复下载弹窗和进度。下载弹窗的叉叉可撤销下载任务并删除未完成的安装包；下载完成后通过 `FileProvider` 打开系统安装器。
- 应用统一设置 Material 主色和强调色为蓝色，覆盖设置开关、弹窗按钮和下载进度控件。
- 通知设置、后台成绩设置、课表学期、运行日志和本地成绩编辑使用与登录页一致的蓝色主题、浅色背景、满宽卡片和圆角。
- 考试提醒提前量使用与其他单选设置一致的白色弹窗，选择后立即保存并更新提醒计划。

## Android 资源与构建

`npm run export:android` 把**空数据页面模板**、公共首页图片和 `cache-meta.json` 写入 `android/app/src/main/assets/`，并在 `artifacts/android-update/` 生成资源包和清单。导出时会移除 `assets/` 中旧的个人课表 HTML/JSON、考试与成绩 JSON；`artifacts/` 中抓取的个人数据不会被打入 APK。有关资源更新的代码见 `src/export-android.ts`、`ResourceUpdateStore.kt` 和 `MainActivity.kt`。

如果修改网页模板，按顺序执行：

```bash
npm run render:ui
npm run export:android
```

随后在 `android/` 目录，使用 JDK 17 和 Android SDK 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。`render:ui`、`export:android` 和 APK 构建必须串行；否则应用可能包含上一次生成的页面。

本机双平台发布工具集中在被 Git 忽略的 `release-tools/`，不随仓库分发。根目录的 `npm run release` 读取 `release-tools/release-notes.md`，自动增加 `versionName` 和 `versionCode` 后串行生成资源、构建并校验 APK；APK 和热更新附件上传到 GitHub/Gitee。脚本仅提交版本配置，推送当前分支和新标签，失败用 `--resume` 继续同一版本。配置和说明位于本机的 `release-tools/README.md`，输出及续传记录位于 `release-tools/output/`；默认沿用当前本机调试签名。

## 主要源码

| 位置 | 作用 |
| --- | --- |
| `MainActivity.kt`、`activity_main.xml` | 主界面、WebView、缓存更新和页面导航。 |
| `UniversityEndpoints.kt`、`HeadlessLoginClient.kt` | 站点入口和纯 HTTP 认证。 |
| `CredentialStore.kt`、`FailureDetails.kt` | 本地密码加密、迁移和错误提示分类。 |
| `Timetable*`、`Exam*` | 课表/考试解析、渲染、学期与提醒。 |
| `HeadlessScoreSync*`、`ScoreSyncSettings*` | 成绩后台同步、调度和设置。 |
| `NotificationSettingsActivity.kt`、`CourseNotification*`、`ExamNotification*` | 上课与考试通知。 |
| `AppDebugLog.kt`、`LogViewerActivity.kt` | 运行日志。 |

以上 Kotlin 文件在 `android/app/src/main/java/com/classsche/mobile/`；完整索引见 [FILE-INDEX.md](FILE-INDEX.md)。

## 排错

- 登录 WebView 主页面失败会显示连接超时、WebView 错误码或 HTTP 状态码；统一认证返回 HTTP 401 等登录失败页面时优先显示校方表单内的错误原文。提交表单后找到输入框并点击按钮只代表请求已发起，不代表账号密码验证成功。异常跳转会显示目标地址。登录页出现但业务表格缺失时，不应把登录页写入缓存。
- 课表更新后的考试、成绩、等级考试同步结果分别显示；解析或网络失败可在“运行日志”查看具体步骤和错误。原有缓存应保留。
- 网页已更新但 APK 还是旧版：检查 `render:ui → export:android → assembleDebug` 的执行顺序，并确认安装的是新 APK。
- ADB `unauthorized` 需要在手机上确认 USB 调试授权；`INSTALL_FAILED_ABORTED` 通常表示设备端取消了安装确认。

桌面端的 Playwright 登录态文件不能直接当作 Android Cookie 使用。站点迁移与已验证页面见 [LOGIN-MIGRATION.md](LOGIN-MIGRATION.md)。
