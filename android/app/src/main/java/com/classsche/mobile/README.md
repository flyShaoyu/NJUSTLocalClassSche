# `com.classsche.mobile` 目录说明

这部分是安卓原生实现，负责读取导出的 `assets`、渲染原生首页、展示 WebView 页面，以及课表/考试通知。

## 核心入口

- `MainActivity.kt`：应用主界面和主要业务入口。负责首页原生 UI、WebView 切换、缓存读取、考试/课表入口、GitHub 更新检查等。
- `NotificationSettingsActivity.kt`：通知设置页，管理上课提醒、考试提醒、精确闹钟和提醒提前量。

## 考试相关

- `ExamArrangement.kt`：考试安排数据模型。
- `ExamParser.kt`：读取并解析 `assets/exam-list.json`。
- `ExamRenderer.kt`：把考试数据渲染到原生考试列表和首页最近考试区域。
- `ExamNotificationHelper.kt`：考试通知公用计算逻辑，负责时间解析、最近考试筛选等。
- `ExamNotificationScheduler.kt`：考试提醒调度器，创建和取消考试通知闹钟。
- `ExamOngoingNotificationScheduler.kt`：考试进行中常驻通知调度器。
- `ExamNotificationReceiver.kt`：考试提醒广播接收器，接到闹钟后拉起通知服务。
- `ExamNotificationService.kt`：考试提醒通知服务，负责展示考试提醒通知。
- `ExamForegroundNotificationService.kt`：考试常驻通知前台服务。

## 课表相关

- `TimetableCourse.kt`：课表课程数据模型。
- `TimetableParser.kt`：读取并解析 `assets/timetable.json`。
- `TimetableRenderer.kt`：把课表数据渲染到原生最近课表区域。
- `TimetableScheduleHelper.kt`：课表时间计算工具，负责筛选最近课程、计算上下课时间。
- `CourseNotificationScheduler.kt`：上课提醒调度器，创建和取消课程通知闹钟。
- `CourseNotificationAlarmReceiver.kt`：上课提醒广播接收器。
- `CourseNotificationBootReceiver.kt`：开机后恢复上课提醒调度。
- `CourseNotificationService.kt`：上课提醒通知服务。

## 主页图片

- `HomeImagePagerAdapter.kt`：首页图片轮播适配器预留文件；当前逻辑已经主要并回 `MainActivity.kt`，这里只保留包声明占位。
