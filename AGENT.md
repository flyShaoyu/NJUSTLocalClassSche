# AGENT

本文档用于说明这个仓库后续协作时的约定，方便人和 AI 接手维护。

## 项目目标

本仓库维护的是一整套可运行链路，而不是单个脚本：

1. 登录教务系统
2. 获取课表 HTML
3. 解析为 JSON
4. 渲染为本地课表页面
5. 导出到 Android 并打包 APK

## 维护优先级

修改时优先保证：

1. 不破坏桌面端抓取
2. 不破坏 `timetable.json` 结构
3. 不破坏 Android 本地缓存显示
4. UI 调整尽量集中在 `src/timetable-ui.ts` 和 `src/timetable-ui-script.ts`

## 关键文件

### 桌面端

- `src/index.ts`
  抓取总入口
- `src/login.ts`
  登录逻辑
- `src/html-parser.ts`
  HTML 解析逻辑
- `src/render-ui.ts`
  渲染入口
- `src/export-android.ts`
  Android 导出入口

### Android

- `android/app/src/main/java/com/classsche/mobile/MainActivity.kt`
  Android 主流程
- `android/app/src/main/java/com/classsche/mobile/TimetableParser.kt`
  Android 解析器
- `android/app/src/main/java/com/classsche/mobile/TimetableRenderer.kt`
  Android 本地页面渲染器

## 工作流约定

### 改解析逻辑

```bash
npm run parse
npm run render:ui
```

### 改前端课表 UI

```bash
npm run render:ui
npm run export:android
```

### 改 Android 行为

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

## 非常重要

`npm run render:ui` 和 `npm run export:android` 不能并行执行。

原因：

- `export:android` 依赖 `render:ui` 生成的新 HTML
- 并行执行会导致 Android 资源吃到上一个版本
- 现象通常是“网页已经更新，但 app 落后一版”

## 提交建议

- UI 微调尽量单独提交
- 解析逻辑调整尽量单独提交
- Android 行为修改尽量单独提交

这样后续定位回归问题更快。

## 注意事项

- 不要直接手改 `artifacts/timetable-view.html` 作为长期方案
- 要改 UI，请修改 `src/timetable-ui.ts`
- 课程性质请使用 `courseType`
- 不要再用 `courseSequence` 假装课程性质

## 不应提交到仓库的内容

- `.env`
- `artifacts/`
- `android/.gradle/`
- `android/build/`
- `android/app/build/`
- APK
- 登录态
