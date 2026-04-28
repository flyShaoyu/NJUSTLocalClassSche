# API

这里的“API”主要指仓库内部可直接调用的脚本入口和关键模块，不是对外 HTTP 接口。

## npm scripts

### `npm run start`

桌面端总入口。

职责：

- 读取配置
- 启动 Playwright
- 复用登录态
- 必要时等待人工登录
- 抓取课表 HTML
- 解析 `timetable.json`
- 渲染本地课表页面

### `npm run parse`

使用已有 `artifacts/timetable.html` 重新生成：

- `artifacts/timetable.json`
- `artifacts/timetable-view.html`
- `artifacts/home-view.html`

### `npm run render:ui`

使用已有 `artifacts/timetable.json` 重新生成：

- `artifacts/timetable-view.html`
- `artifacts/home-view.html`

### `npm run export:android`

将以下产物导出到 Android `assets/`：

- `timetable-view.html`
- `home-view.html`
- `timetable.json`
- `timetable.html`
- `resources/*`

## 关键模块

### `src/config.ts`

负责读取环境变量配置。

主要配置项：

- `LOGIN_URL`
- `TIMETABLE_URL`
- `LOGIN_SUCCESS_SELECTOR`
- `MANUAL_LOGIN_TIMEOUT_MS`

### `src/login.ts`

桌面端登录逻辑。

能力包括：

- 自动预填账号密码
- 等待人工输入验证码
- 识别登录成功
- 保存登录态

### `src/html-parser.ts`

从教务 HTML 解析结构化课表。

当前主要字段：

- `courseName`
- `weekday`
- `periods`
- `classroom`
- `weeks`
- `teacher`
- `courseCode`
- `courseSequence`
- `courseType`
- `rawText`

### `src/timetable-ui.ts`

课表页面生成器。

职责：

- 根据 `timetable.json` 生成移动端课表 HTML
- 渲染周视图和全学期视图
- 渲染课程详情页
- 渲染今天高亮与当前时间线

### `src/timetable-ui-script.ts`

课表页面前端交互脚本。

职责：

- 周切换 / 全学期切换
- 冲突课程展开
- 详情页翻页、圆点切换、滑动切换
- 当前选中课程块同步高亮

### `src/export-android.ts`

把本地产物同步到 Android `assets/`。

### `android/app/src/main/java/com/classsche/mobile/MainActivity.kt`

Android 主入口。

职责：

- 登录页与课表页切换
- 隐藏 WebView 登录
- 验证码显示
- 本地缓存显示
- 启动后重绘最新本地页面

### `android/app/src/main/java/com/classsche/mobile/TimetableParser.kt`

Android 解析器。

作用：

- 读取课表 HTML
- 解析为 Android 本地 `TimetableCourse`

### `android/app/src/main/java/com/classsche/mobile/TimetableRenderer.kt`

Android 本地课表渲染器。

作用：

- 由本地缓存 JSON 生成本地课表 HTML
- 生成空状态页

## 数据文件

### `artifacts/timetable.html`

原始课表 HTML。

### `artifacts/timetable.json`

解析后的结构化课表数据。

### `artifacts/timetable-view.html`

本地课表页面。

### `artifacts/home-view.html`

本地首页页面。
