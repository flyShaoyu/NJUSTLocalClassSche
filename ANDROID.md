# Android 说明

## 当前状态

仓库已经包含完整的 Android 工程，位于 `android/`。

当前 APK 能力包括：

- 在手机端显示本地缓存课表页面
- 本地登录表单输入账号、密码、验证码
- 通过隐藏 `WebView` 打开真实教务页
- 读取验证码图片
- 提交真实登录表单
- 打开课表页并刷新展示

## Android 资源来源

以下文件会被导出到 Android `assets/`：

- `artifacts/timetable-view.html`
- `artifacts/timetable.json`
- `artifacts/timetable.html`

导出目标目录：

- `android/app/src/main/assets/`

## 相关核心文件

- `android/app/src/main/java/com/classsche/mobile/MainActivity.kt`
  Android 主入口
- `android/app/src/main/res/layout/activity_main.xml`
  Android 主布局
- `src/export-android.ts`
  桌面端导出 Android 资源
- `src/timetable-ui.ts`
  本地课表页面生成器

## 手机端当前流程

1. 进入登录页
2. 隐藏 `WebView` 加载真实教务页
3. 获取验证码图片并展示到本地界面
4. 输入账号、密码、验证码并提交
5. 登录成功后访问课表页
6. 读取最新页面并刷新缓存展示

## 导出最新资源

如果桌面端已经拿到最新课表：

```bash
npm run export:android
```

如果希望先抓再导出：

```bash
npm run start
npm run export:android
```

## 本地构建 APK

在 `android/` 目录下执行：

```bash
./gradlew.bat :app:assembleDebug
```

生成的 APK 默认位于：

- `android/app/build/outputs/apk/debug/app-debug.apk`

## 安装到手机

确保：

- 已开启开发者选项
- 已开启 USB 调试
- 手机端已允许当前电脑的 ADB 授权

安装命令示例：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## 常见问题

### 1. 手机显示的还是旧页面

当前项目已经在 Android 端对缓存页做了强制刷新，但如果手机还停留在旧页面，可以：

1. 重新导出资源
2. 重新构建 APK
3. 重新安装
4. 重新打开 app 的“本地缓存”页

### 2. ADB 提示 unauthorized

需要在手机上确认 USB 调试授权。

### 3. ADB 提示 INSTALL_FAILED_ABORTED

通常是手机端安装确认被拒绝，需要重新点击允许。

## 限制说明

桌面端抓取依赖 Playwright，不能原样打进普通 Android APK。

因此本项目的移动端实现是：

- 保留桌面端 Playwright 抓取链路
- 同时提供 Android 专用的 WebView 登录与展示链路

也就是说，Android 不是“直接运行 Playwright”，而是“移动端重做登录和展示逻辑”。
