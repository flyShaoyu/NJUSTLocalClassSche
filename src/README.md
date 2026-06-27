# `src` 目录说明

这套 TypeScript 代码负责抓取教务页面、解析课表和考试数据、渲染 Web UI，并把生成结果导出到安卓 `assets`。

## 常用流程

- `src/index.ts`：完整抓取入口。登录后抓取课表和考试，解析后写入 `artifacts/`。
- `src/render-ui.ts`：根据已有 JSON 重新渲染首页、课表页、考试页，同时处理首页轮播图缩略图。
- `src/export-android.ts`：把 `artifacts/` 里的 HTML、JSON 和图片同步到 `android/app/src/main/assets/`。
- `src/fetch-exams.ts`：只刷新考试安排。
- `src/parse-html.ts`：对已经保存的 HTML 重新解析，适合调试解析逻辑。

## Node / npm 指令

- 安装依赖：`npm install`
- 类型检查：`npm run check`
- 编译 TypeScript：`npm run build`
- 完整抓取课表和考试：`npm run start`
- 只抓取考试安排：`npm run fetch:exams`
- 用本地 HTML 重新解析：`npm run parse`
- 根据本地 JSON 重新渲染页面：`npm run render:ui`
- 导出到安卓 `assets`：`npm run export:android`

推荐顺序：

1. 首次使用先执行 `npm install`
2. 抓取或解析数据后执行 `npm run render:ui`
3. 需要同步安卓资源时再执行 `npm run export:android`

## 文件用途

- `browser.ts`：封装 Playwright 浏览器和持久化登录态的启动逻辑。
- `config.ts`：集中定义环境变量、账号配置和各类 `artifacts`/资源路径。
- `exam-page.ts`：进入考试安排页面并保持会话，负责页面导航。
- `exam-parser.ts`：把考试 HTML 解析成结构化考试数据，并结合课表补齐教师信息。
- `exam-ui.ts`：生成考试安排页面 HTML。
- `export-android.ts`：把渲染结果和首页图片导出到安卓 `assets`。
- `fetch-exams.ts`：独立抓取考试页面并输出考试 HTML/JSON。
- `fs-utils.ts`：文件存在判断、创建 `artifacts`、写文本文件等通用文件工具。
- `home-page-ui-script.ts`：主页前端脚本模板，负责轮播图、最近课表、菜单等交互。
- `home-page-ui.ts`：当前主页 HTML 生成器，`render:ui` 使用的是这一套。
- `home-ui-script.ts`：旧版主页脚本模板，当前主流程未直接使用，保留作历史参考。
- `home-ui.ts`：旧版主页 HTML 生成器，当前主流程未直接使用，保留作历史参考。
- `html-parser.ts`：课表 HTML 解析器，把原始表格拆成 `TimetableCourse` 列表。
- `index.ts`：总入口，串起登录、抓取、解析、渲染。
- `logger.ts`：统一的命令行日志输出。
- `login.ts`：登录页选择器、自定义账号密码填充和登录成功判断。
- `parse-html.ts`：读取本地保存的课表/考试 HTML 重新解析并输出结果。
- `render-ui.ts`：读取本地 JSON 重新渲染各页面，同时生成首页轮播图变体图。
- `timetable-page.ts`：进入课表页面并保持会话，负责页面导航。
- `timetable-ui-script.ts`：课表页前端脚本模板，负责周次切换、课程展开等交互。
- `timetable-ui.ts`：生成课表页面 HTML。
- `types.ts`：项目共享类型定义，包括配置、课表和考试数据结构。
