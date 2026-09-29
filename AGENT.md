# AGENT

本文档用于说明这个仓库后续协作时的约定，方便人和 AI 接手维护。

## 项目目标

本仓库维护的是一整套可运行链路，而不是单个脚本。新对话先读 [FILE-INDEX.md](FILE-INDEX.md)，再按任务打开相关文件：

1. 登录教务系统
2. 获取课表、考试、成绩及等级考试页面
3. 解析为 JSON，渲染为本地页面
4. 导出不含个人数据的 Android 页面模板及公共资源
5. Android 本地登录、缓存、通知、后台同步与 APK 构建

## 维护优先级

修改时优先保证：

1. 不破坏桌面端抓取
2. 不破坏 `timetable.json` 结构
3. 不破坏 Android 本地缓存显示
4. UI 调整尽量集中在 `src/timetable-ui.ts` 和 `src/timetable-ui-script.ts`
5. 网络/解析错误应指出失败步骤、目标地址或状态码，不要把失败页解析为空数据覆盖缓存

## 关键文件

### 桌面端

- `src/index.ts`
  抓取总入口
- `src/login.ts`
  登录逻辑
- `src/endpoints.ts`、`src/authenticated-page.ts`、`src/diagnostics.ts`
  统一认证、教务 SSO、业务页面校验及错误分类
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
- `android/app/src/main/java/com/classsche/mobile/CredentialStore.kt`
  Android Keystore 密码存储与旧数据迁移

## 工作流约定

### 改解析逻辑

```bash
npm run parse
npm run render:ui
```

先用不含凭据的样例或本机已保存的 HTML 验证解析结果；修改 JSON 字段时同步检查 Android 模型、解析和渲染。

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

通用验证：`npm run check`；需要运行 Node 测试时先 `npm run build`，再执行 `node --test tests/*.test.mjs`。

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
- 当前统一身份认证使用 `.env` 中的 `PASSWORD`（智慧理工服务门户密码）；不要新增第二个密码变量或把本机 `.env` 写入文档
- `export:android` 必须保留去个人数据逻辑；APK 里只能放空数据模板和公共资源
- 修改功能入口、文件职责或构建流程后，同步更新 README、API/ANDROID、FILE-INDEX 和 CHANGELOG

## 不应提交到仓库的内容

- `.env`
- `artifacts/`
- `android/.gradle/`
- `android/build/`
- `android/app/build/`
- APK
- 登录态
- 个人课表、考试、成绩和资源更新生成包
