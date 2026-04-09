# API

这里的“API”主要指仓库内可直接调用的脚本入口和关键模块，不是对外 HTTP 接口。

## npm Scripts

### `npm run start`

桌面端总入口。

职责：

- 读取配置
- 启动 Playwright
- 复用登录态
- 必要时等待人工登录
- 抓取课表 HTML
- 解析 JSON
- 渲染本地课表页面

### `npm run parse`

使用已有 `artifacts/timetable.html` 重新生成：

- `artifacts/timetable.json`
- `artifacts/timetable-view.html`

### `npm run render:ui`

使用已有 `artifacts/timetable.json` 重新生成：

- `artifacts/timetable-view.html`

### `npm run export:android`

把以下文件导出到 Android `assets/`：

- `timetable-view.html`
- `timetable.json`
- `timetable.html`

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
- 等待手工输入验证码
- 识别登录成功
- 保存登录态

### `src/html-parser.ts`

负责从教务页面 HTML 解析结构化课表。

当前输出字段包括：

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

- 根据 JSON 生成移动端课表 HTML
- 渲染周视图
- 渲染详情页弹层
- 渲染“今天”高亮与当前时间线

### `src/export-android.ts`

把本地产物同步到 Android 工程资源目录。

### `android/app/src/main/java/com/classsche/mobile/MainActivity.kt`

Android 主入口。

职责：

- 登录页与课表页切换
- 隐藏 WebView 登录
- 验证码加载
- 打开课表页
- 加载本地缓存页

## 数据文件

### `artifacts/timetable.html`

原始课表 HTML。

### `artifacts/timetable.json`

解析后的结构化数据。

### `artifacts/timetable-view.html`

本地前端页面。
