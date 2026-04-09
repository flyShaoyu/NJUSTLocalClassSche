# Prompts

下面是这个仓库里常用的一些任务提示词，方便后续继续让 AI 接手。

## 抓课表

```text
跑一遍登录流程，抓最新课表并更新 HTML、JSON、前端页面。
```

## 只重解析

```text
不要重新登录，直接用 artifacts/timetable.html 重新生成 timetable.json 和 timetable-view.html。
```

## 只调 UI

```text
只修改 src/timetable-ui.ts，把手机端课表页面继续优化，不要改解析逻辑。
```

## 导出 Android

```text
把当前本地课表导出到 android assets，并重新构建 debug APK。
```

## 安装到手机

```text
用 adb 把最新 APK 安装到已连接手机。
```

## 抓真机截图

```text
用 adb 抓当前手机屏幕截图，我要你根据截图继续调 UI。
```

## 修复课程性质

```text
检查 timetable.html 里课程性质列，确保 parser 把 courseType 解析进 timetable.json，并在详情页正确显示。
```

## 调整详情页

```text
只优化详情页弹层，不要动主课表布局。
```

## 调整课表网格

```text
只优化主课表网格、课程卡片、今天高亮、时间线，不要动登录页和 Android 原生布局。
```

