# `com.classsche.mobile` 源码说明

Android 原生代码负责 WebView 登录、应用私有缓存、首页和业务页面、通知、后台成绩同步以及版本/资源更新。更完整的流程见仓库根目录 [ANDROID.md](../../../../../../../../ANDROID.md) 与 [FILE-INDEX.md](../../../../../../../../FILE-INDEX.md)。

## 登录、缓存与更新

- `MainActivity.kt`：界面和主要业务入口；处理统一认证 WebView、业务页捕获、原生首页、缓存及更新检查。
- `UniversityEndpoints.kt`：统一认证、教务 SSO、课表、考试和成绩 URL。
- `CredentialStore.kt`：Android Keystore AES-GCM 密码保存和旧明文迁移；使用智慧理工服务门户密码。
- `HeadlessLoginClient.kt`：纯 HTTP 认证与教务会话获取；`HeadlessScoreSyncManager.kt`、`HeadlessScoreSyncScheduler.kt`、`HeadlessScoreSyncReceiver.kt`：后台成绩同步。
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
