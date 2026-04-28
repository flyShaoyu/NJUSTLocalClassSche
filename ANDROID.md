# Android 说明

## 当前状态

仓库已经包含完整 Android 工程，路径为 `android/`。

当前 Android 端能力：

- 本地登录页输入账号、密码、验证码
- 隐藏 `WebView` 打开真实教务页
- 显示验证码图片并提交真实表单
- 登录成功后访问课表页
- 抓取并解析课表
- 显示本地课表缓存页

## Android 资源来源

以下文件会被导出到 Android `assets/`：

- `artifacts/timetable-view.html`
- `artifacts/home-view.html`
- `artifacts/timetable.json`
- `artifacts/timetable.html`
- `artifacts/resources/*`

导出目标目录：

- `android/app/src/main/assets/`

## 关键文件

- `android/app/src/main/java/com/classsche/mobile/MainActivity.kt`
  Android 主入口
- `android/app/src/main/java/com/classsche/mobile/TimetableParser.kt`
  Android 课表解析器
- `android/app/src/main/java/com/classsche/mobile/TimetableRenderer.kt`
  Android 本地课表 HTML 生成器
- `android/app/src/main/res/layout/activity_main.xml`
  主布局
- `src/export-android.ts`
  导出 Android 资源脚本

## 正确构建顺序

Android 资源导出必须串行执行，不要并行。

正确顺序：

```bash
npm run render:ui
npm run export:android
```

然后再构建：

```powershell
cd D:\document\CLassSche\android
$env:JAVA_HOME='C:\Users\43631\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

## APK 位置

- `android/app/build/outputs/apk/debug/app-debug.apk`

## 安装到手机

确保：

- 已开启开发者选项
- 已开启 USB 调试
- 已接受当前电脑的 ADB 授权

安装命令：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## 缓存策略

当前策略是：

- 升级 APK 时保留 `timetable.json` 等本地缓存数据
- 应用启动后再根据最新模板重绘本地页面
- 不在 `onCreate` 最早阶段强行重绘，避免启动闪退和资源竞争

## 常见问题

### 1. 网页是新的，app 还是旧的

优先检查：

1. 是否按顺序执行了 `npm run render:ui`
2. 是否紧接着执行了 `npm run export:android`
3. 是否重新构建并安装了 APK

### 2. ADB 提示 unauthorized

需要在手机上重新确认 USB 调试授权。

### 3. ADB 提示 INSTALL_FAILED_ABORTED

通常是手机上的安装确认被拒绝。

### 4. 首页启动闪退

近期已修复一类问题：

- 若 `thumb` 图缺失，自动回退到 `detail`
- 若 `detail` 也缺失，再回退到原图

因此资源不完整时不应再直接闪退。

## 限制说明

桌面端抓取依赖 Playwright，不能直接原样塞进 Android。

因此本项目的移动端实现是：

- 桌面端保留 Playwright 抓取链路
- Android 端使用本地表单 + WebView + 本地缓存显示链路
