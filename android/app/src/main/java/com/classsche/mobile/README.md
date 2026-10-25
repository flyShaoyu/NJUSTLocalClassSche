# `com.classsche.mobile` 源码说明

Android 原生代码负责 WebView 登录、应用私有缓存、首页和业务页面、通知、后台成绩同步以及版本/资源更新。更完整的流程见仓库根目录 [ANDROID.md](../../../../../../../../ANDROID.md) 与 [FILE-INDEX.md](../../../../../../../../FILE-INDEX.md)。

## 登录、缓存与更新

- `MainActivity.kt`：界面和主要业务入口；处理统一认证 WebView、业务页捕获、原生首页、缓存及更新检查。
- `UniversityEndpoints.kt`：统一认证、教务 SSO、课表、考试和成绩 URL。
- `CredentialStore.kt`：Android Keystore AES-GCM 密码保存和旧明文迁移；使用智慧理工服务门户密码。
- `IdentityQrLoginController.kt`、`IdentityQrPageScript.kt`：首次加载认证表单就使用电脑端 User-Agent；弹窗直接读取网页当前二维码，不重载整页或再次下载图片。过期后“重新获取”复用校方 `refresh()`，刷新期间禁止重复申请及保存旧码；申请、图片加载和状态轮询分别显示超时、HTTP 或解析错误。准备密码表单暂停扫码，用户点击扫码后才恢复；保存按钮使用当前图片，不中断轮询。
- 校方状态 `0/2/1/3` 分别表示等待扫码/已扫码待确认/微信已确认/失效。`IdentityQrLoginController.onHostResumed` 返回前台立即查原二维码，原生 Handler 每秒驱动同会话 `isUsed()` 并限制一个在途请求，不依赖隐藏 WebView 的定时器。收到 `1` 后单次提交当前二维码表单，保留 Cookie、service 和隐藏字段；`MainActivity.continueQrLoginNavigation` 在门户/教务主页可用后继续 SSO，不等门户资源全部加载。弹窗与 `QR_AUTH` 日志显示确认、提交、教务会话和课表加载步骤，单步 20 秒或总流程 60 秒超时明确报错，排除用户切后台的时间。
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

## 成绩与其他页面

- `ScoreSyncSettings.kt`、`ScoreSyncSettingsActivity.kt`：后台成绩同步设置。
- `ScoreEditorActivity.kt`、`LocalScoreEditorActivity.kt`：本地成绩条目编辑，不回写教务网站。
- `HomeImagePagerAdapter.kt`：保留的轮播适配器占位文件；主要首页逻辑在 `MainActivity.kt`。

Android 个人数据写入应用私有目录；导入 APK 的 `assets` 只含空数据页面模板和公共资源。改网页模板须先在仓库根目录依次运行 `npm run render:ui`、`npm run export:android`，再构建 APK。

登录回归：先构建 `:app:assembleDebug`，再从仓库根目录运行 `node --test tests/android-login.test.mjs`。测试只访问本机模拟响应，不提交真实账号密码。

二维码回归：`node --test tests/identity-qr.test.mjs`，执行实际 Android 页面脚本，使用离线页面检查图片复用、过期刷新、重复表单、错误及恢复，不截屏。
