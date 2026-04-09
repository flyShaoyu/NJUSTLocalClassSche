# ClassSche

一个用于抓取、解析、渲染并打包课表的项目。

它包含两条主线：

- 桌面端：`Node.js + TypeScript + Playwright`
- 移动端：`Android WebView APK`

当前能力包括：

- 打开教务登录页
- 复用已保存登录态
- 登录失效时回退到人工登录
- 抓取课表 HTML
- 解析生成 `timetable.json`
- 渲染本地课表页面
- 导出到 Android 资源并打包 APK

## 下载发布版

- GitHub Releases：
  [https://github.com/flyShaoyu/NJUSTLocalClassSche/releases](https://github.com/flyShaoyu/NJUSTLocalClassSche/releases)

## 目录说明

- `src/`
  核心脚本，包含登录、抓取、解析、渲染、Android 导出逻辑。
- `artifacts/`
  本地抓取与渲染产物，例如 `timetable.html`、`timetable.json`、`timetable-view.html`。
- `android/`
  Android 工程，用于把本地课表页面封装成 APK，并在手机端执行登录与缓存展示。

## 环境要求

- Node.js 20+
- npm
- Playwright Chromium
- Android Studio / Android SDK
- JDK 17

## 初始化

1. 安装依赖

```bash
npm install
npx playwright install chromium
```

2. 复制环境变量模板

```bash
copy .env.example .env
```

3. 按需修改 `.env`

- `LOGIN_URL`
- `TIMETABLE_URL`
- `LOGIN_SUCCESS_SELECTOR`
- `MANUAL_LOGIN_TIMEOUT_MS`

## 常用命令

抓取课表并更新本地缓存：

```bash
npm run start
```

仅解析已抓到的 HTML：

```bash
npm run parse
```

仅重绘前端课表页面：

```bash
npm run render:ui
```

导出 Android 资源：

```bash
npm run export:android
```

类型检查：

```bash
npm run check
```

## 主要产物

- `artifacts/timetable.html`
  原始课表 HTML
- `artifacts/timetable.json`
  解析后的课表数据
- `artifacts/timetable-view.html`
  本地课表前端页面
- `artifacts/storageState.json`
  Playwright 登录态

## 登录流程

桌面端流程：

1. 优先复用 `artifacts/storageState.json`
2. 若登录态失效，打开登录页
3. 允许手工输入账号、密码、验证码
4. 登录成功后重新保存登录态
5. 访问课表页并抓取 HTML
6. 解析并渲染本地课表页面

移动端流程：

1. 本地登录表单输入账号、密码、验证码
2. 隐藏 `WebView` 加载真实登录页
3. 从真实页面读取验证码图
4. 提交真实表单并跳转课表页
5. 读取课表 HTML 并刷新本地缓存展示

## 安全说明

- `.env`、`artifacts/`、构建产物、APK 都已加入 `.gitignore`
- 本仓库默认不提交账号、密码、登录态、抓取结果
- 如需分享仓库，请确认未手动加入敏感文件

## 相关文档

- `ANDROID.md`
  Android 说明
- `API.md`
  主要模块和脚本接口
- `AGENT.md`
  协作与维护约定
- `PROMPTS.md`
  常用提示词与操作入口
- `CHANGELOG.md`
  当前版本变更记录

## 写在最后
 - 本仓库完全由codex生成，几乎无人工痕迹
 - 本app UI来源于周三课表，(就是抄的)
 - 目前仅支持NJUST，有问题提issue，欢迎pr，我(codex)会审的
 - 没了

## P.S. 留一点你们可能会喜欢的项目
 - https://github.com/Samueli924/chaoxing
 - https://github.com/aquamarine5/ChaoxingSignFaker
 - https://github.com/VermiIIi0n/fuckZHS
