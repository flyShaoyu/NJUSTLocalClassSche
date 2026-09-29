# Android 说明

Android 工程位于 `android/`，桌面抓取工程位于 `src/`。应用提供原生首页、登录、课表/考试/成绩/等级考试页面、本地缓存、课程与考试通知、成绩后台同步，以及版本和资源更新检查。

## 登录与本地数据

- 登录入口由 `UniversityEndpoints.kt` 统一定义。应用使用 WebView 完成统一身份认证，再通过教务 SSO 建立教务域名的会话；校方要求额外验证时会在同一会话内打开认证网页。登录页仅保留提交登录和加载登录页入口，不再显示本地验证码，也不再进行 OCR 识别。页面使用与主页相同的背景和卡片宽度、蓝色控件，以及黄色的校方限制提示。
- 登录页使用**智慧理工服务门户密码**。`CredentialStore.kt` 用 Android Keystore 中的不可导出密钥进行 AES-GCM 加密；旧版保存在偏好设置中的明文密码在首次读取时迁移并删除。后台同步需要在未打开界面时解密，因此密钥不要求每次生物识别。
- 登录页加载和提交期间会禁用重复提交；首页“更新课表”优先复用已有教务会话，必要时使用保存的凭据在后台完成统一认证、所选学期课表抓取及相关缓存同步，不打开登录页。同步期间显示“更新中…”，重复点击只提示当前操作；失败时保留原课表缓存并显示具体错误。
- `HeadlessLoginClient.kt` 和 `HeadlessScoreSyncManager.kt` 处理纯 HTTP 登录及成绩同步，并遵守 Cookie 的域名/路径范围；课表静默更新复用该登录器，并用返回的教务 Cookie 拉取考试、成绩及等级考试。校方要求额外认证时静默更新会报错并保留原缓存，用户可另行在登录页完成验证。
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

- 登录 WebView 主页面失败会显示连接超时、WebView 错误码或 HTTP 状态码；异常跳转会显示目标地址。登录页出现但业务表格缺失时，不应把登录页写入缓存。
- 课表更新后的考试、成绩、等级考试同步结果分别显示；解析或网络失败可在“运行日志”查看具体步骤和错误。原有缓存应保留。
- 网页已更新但 APK 还是旧版：检查 `render:ui → export:android → assembleDebug` 的执行顺序，并确认安装的是新 APK。
- ADB `unauthorized` 需要在手机上确认 USB 调试授权；`INSTALL_FAILED_ABORTED` 通常表示设备端取消了安装确认。

桌面端的 Playwright 登录态文件不能直接当作 Android Cookie 使用。站点迁移与已验证页面见 [LOGIN-MIGRATION.md](LOGIN-MIGRATION.md)。
