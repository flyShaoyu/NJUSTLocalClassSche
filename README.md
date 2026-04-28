# ClassSche

一个面向 NJUST 教务课表的本地化项目，包含两条主链路：

- 桌面端：`Node.js + TypeScript + Playwright`
- 移动端：`Android WebView APK`

项目目标链路为：

1. 登录教务系统
2. 抓取课表 HTML
3. 解析为结构化 JSON
4. 渲染为本地课表页面
5. 导出到 Android 并打包 APK

## 发布下载

- GitHub Releases：
  [https://github.com/flyShaoyu/NJUSTLocalClassSche/releases](https://github.com/flyShaoyu/NJUSTLocalClassSche/releases)

## 当前能力

- 复用桌面端登录态 `artifacts/storageState.json`
- 登录态失效后切回人工登录
- 抓取课表页并保存原始 HTML
- 解析课程名称、星期、节次、教室、周次、教师、课程代码、课程序号、课程性质
- 生成移动端风格的本地课表页面
- 导出 Android `assets`
- Android 端本地登录、验证码显示、课表缓存显示

## 目录结构

- `src/`
  核心脚本，包含登录、抓取、解析、渲染、Android 导出逻辑
- `artifacts/`
  本地产物目录，包含抓取结果、解析结果、渲染页面
- `android/`
  Android 工程

## 环境要求

- Node.js 20+
- npm
- Playwright Chromium
- Android Studio / Android SDK
- JDK 17

## 初次使用

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

抓取课表并更新本地产物：

```bash
npm run start
```

仅重解析已有 HTML：

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

## Android 构建

`gradlew.bat` 在 `android/` 目录下，不在仓库根目录。

推荐命令：

```powershell
cd D:\document\CLassSche\android
$env:JAVA_HOME='C:\Users\43631\.jdks\jbr-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

## 产物说明

- `artifacts/timetable.html`
  原始课表 HTML
- `artifacts/timetable.json`
  解析后的结构化课表
- `artifacts/timetable-view.html`
  本地课表页面
- `artifacts/home-view.html`
  本地首页页面
- `artifacts/storageState.json`
  Playwright 登录态

## 串行执行说明

这一步很重要：`render:ui` 和 `export:android` 不能并行跑。

正确顺序是：

1. `npm run render:ui`
2. `npm run export:android`
3. `./gradlew.bat :app:assembleDebug`

原因是 `export:android` 依赖 `render:ui` 刚生成的新 HTML。若并行执行，Android 很容易打进上一个版本的页面，表现为“网页是新的，app 落后一版”。

## 登录与缓存

桌面端：

1. 优先复用 `artifacts/storageState.json`
2. 若失效则打开登录页
3. 用户手工输入账号、密码、验证码
4. 登录成功后重存登录态
5. 访问课表页并抓取 HTML

Android 端：

1. 本地登录页输入账号、密码、验证码
2. 隐藏 `WebView` 打开真实登录页
3. 拉取验证码图
4. 提交真实表单
5. 登录成功后抓取课表并更新本地缓存

## 安全说明

- `.env`
- `artifacts/`
- Android 构建产物
- APK
- 登录态

以上都不应提交到 git。

## 相关文档

- [ANDROID.md](/d:/document/CLassSche/ANDROID.md)
- [API.md](/d:/document/CLassSche/API.md)
- [AGENT.md](/d:/document/CLassSche/AGENT.md)
- [PROMPTS.md](/d:/document/CLassSche/PROMPTS.md)
- [CHANGELOG.md](/d:/document/CLassSche/CHANGELOG.md)

## 写在最后
 - 本仓库完全由codex生成，几乎无人工痕迹
 - 本app UI来源于周三课表，(就是抄的)
 - 目前仅支持NJUST，有问题提issue，欢迎pr，我(codex)会审的
 - 没了

## P.S. 留一点你们可能会喜欢的项目
 - https://github.com/Samueli924/chaoxing
 - https://github.com/aquamarine5/ChaoxingSignFaker
 - https://github.com/VermiIIi0n/fuckZHS