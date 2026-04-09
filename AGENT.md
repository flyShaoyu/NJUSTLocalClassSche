# AGENT

本文件用于说明这个仓库的协作约定，方便后续继续由人或 AI 维护。

## 目标

这个仓库的目标不是单纯抓课表，而是维护一整套可运行链路：

1. 登录教务系统
2. 获取课表 HTML
3. 解析成结构化 JSON
4. 渲染成移动端友好的课表页面
5. 导出到 Android 并打包 APK

## 修改优先级

改动时优先保证：

1. 不破坏现有抓取链路
2. 不破坏 JSON 解析结构
3. 不破坏 Android 端缓存展示
4. UI 微调尽量只改 `src/timetable-ui.ts`

## 关键约定

### 桌面端

- 登录和抓取入口：`src/index.ts`
- 登录逻辑：`src/login.ts`
- HTML 解析：`src/html-parser.ts`
- 仅重绘 UI：`src/render-ui.ts`

### Android 端

- 主入口：`android/app/src/main/java/com/classsche/mobile/MainActivity.kt`
- 本地资源导出：`src/export-android.ts`

### UI

- 本地课表前端由 `src/timetable-ui.ts` 统一生成
- 不建议直接手改 `artifacts/timetable-view.html`
- 如果改了 `src/timetable-ui.ts`，需要重新执行：

```bash
npm run render:ui
npm run export:android
```

## 常用工作流

### 修改解析逻辑

```bash
npm run parse
npm run render:ui
```

### 修改课表 UI

```bash
npm run render:ui
npm run export:android
```

### 修改 Android 行为

```bash
cd android
./gradlew.bat :app:assembleDebug
```

## 提交建议

- UI 调整尽量单独提交
- 解析结构调整尽量单独提交
- Android 行为修改尽量单独提交

这样后面定位回归问题会轻很多。

## 注意事项

- `.env`、`artifacts/`、APK、登录态都不要提交
- 课程性质要使用 `courseType`，不要再错误使用 `courseSequence`
- 如果手机端显示旧页面，优先检查：
  - 是否重新导出 Android 资源
  - 是否重新构建并安装 APK

