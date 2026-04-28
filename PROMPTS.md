# PROMPTS

下面是这个仓库里常用的一些任务提示词，方便后续继续让 AI 接手。

## 抓最新课表

```text
跑一遍登录流程，抓最新课表并更新 HTML、JSON、本地前端页面。
```

## 只重解析

```text
不要重新登录，直接用 artifacts/timetable.html 重新生成 timetable.json 和 timetable-view.html。
```

## 只调 UI

```text
只修改 src/timetable-ui.ts 和 src/timetable-ui-script.ts，继续优化课表页面，不要改解析逻辑。
```

## 导出 Android

```text
把当前本地课表导出到 Android assets，并重新构建 debug APK。注意 render:ui 和 export:android 要串行执行。
```

## 安装到手机

```text
用 adb 把最新 APK 安装到已连接手机。
```

## 抓真机截图

```text
用 adb 抓当前手机屏幕截图，我要你根据截图继续调 UI。
```

## 修解析逻辑

```text
检查 timetable.html 和 parser，修正 timetable.json 的课程信息映射错误，再同步到 Android 解析器。
```

## 调整详情页

```text
只优化课表详情页弹层，不要改主课表布局。
```

## 调整主课表

```text
只优化主课表网格、课程卡片、今天高亮、时间线，不要动登录页和 Android 原生布局。
```

## 排查 Android 落后一版

```text
检查 render:ui、export:android、assembleDebug 的执行顺序，并确认 app 为什么没有吃到最新 HTML。
```
