# 新对话任务提示

这些示例用于新对话接手项目。先让助手读取 [FILE-INDEX.md](FILE-INDEX.md) 和 [AGENT.md](AGENT.md)，只展开当前任务涉及的文件。`.env`、`artifacts/storageState.json`、个人课表及成绩数据不要上传或贴入对话；如需排错，提供脱敏的错误文案和目标页面即可。

## 抓取与登录

```text
先看 FILE-INDEX.md 和 AGENT.md。用本机现有配置检查统一认证、教务 SSO 及课表、考试、成绩、等级考试抓取；遇到验证码在浏览器内处理。记录每个页面的 HTTP 状态、跳转和解析结果，不输出密码或 Cookie。
```

```text
只用 artifacts/timetable.html 和已有考试/成绩 HTML 排查解析错误，不重新登录。核对 src/types.ts 与 Android 数据模型，保留旧缓存直到解析成功。
```

## 页面与 Android

```text
只调整课表页面的周切换、冲突课程或详情弹层。先看 src/timetable-ui.ts 与 src/timetable-ui-script.ts；完成后依次运行 render:ui、export:android，再按需要构建 APK。
```

```text
排查 Android 登录或后台成绩同步。先看 UniversityEndpoints.kt、MainActivity.kt、CredentialStore.kt、HeadlessLoginClient.kt 和 HeadlessScoreSyncManager.kt。保留分步骤错误提示，不在日志中写密码或完整 Cookie。
```

```text
检查考试/成绩/等级考试页或通知。根据 FILE-INDEX.md 只读取对应 parser、UI、Android 同步及通知文件；说明是抓取失败、网站跳转、HTTP 错误还是解析结构变化。
```

```text
诊断“网页是新的，APK 仍是旧的”。核对 render:ui → export:android → assembleDebug 的串行执行、APK 时间戳和安装版本；确认 assets 中只有空数据模板和公共资源。
```

## 文档与历史

```text
对照 git log、当前工作区和源码更新 README、API、ANDROID、FILE-INDEX 与 CHANGELOG；把未提交改动和已有提交分开记录，不把测试通过写成真机验证通过。
```
