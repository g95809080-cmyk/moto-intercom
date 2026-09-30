---
version: alpha
name: "MotoCom Android"
description: "离线骑行对讲，浅暖底色、绿色操作与清楚的通话状态。"
colors:
  primary: "#416722"
  accent: "#C3F36B"
  background: "#F3F4EF"
  surface: "#FFFFFF"
  text: "#18201D"
  secondary: "#626B62"
  border: "#DFE3D9"
  danger: "#B3261E"
typography:
  sans:
    fontFamily: "Android sans-serif"
omitted:
  - section: rounded
    reason: "Native dp values are maintained by dimens.xml; the web lint does not support dp. See Shapes."
  - section: spacing
    reason: "Native dp values are maintained by dimens.xml. See Layout."
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#FFFFFF"
  button-home:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.text}"
  button-danger:
    textColor: "{colors.danger}"
  card:
    backgroundColor: "{colors.surface}"
  outline:
    backgroundColor: "{colors.border}"
  dialog:
    backgroundColor: "{colors.surface}"
---

# MotoCom Android 设计约定

## Overview

骑士出发前连接车友、骑行中识别语音状态。沿用现有暖灰背景、绿色和深色通话仪表；信息必须能直接对应服务事实。UI 使用简体中文，设备与骑行场景来自现有项目；未据语言推断地区业务规则。

这是原生 Android 产品。DESIGN.md 镜像现有规范：`app/src/main/res/values/colors.xml` 和 `dimens.xml` → `MotoComTheme.kt` 的 Material 3 适配 → Compose 页面与 `MotoComPanel`。颜色原始值由资源维护，错误色由主题维护；此文件不生成 CSS，也不另设调色板。

## Colors

浅色 surface 承载设置和成员；深色首页仪表只用于双人通话主状态。primary 用于原生按钮、选择，accent 用于首页主操作。danger 用于移除与结束，必须同时写清动作及后果，不单靠颜色表达。

## Typography

采用系统 SansSerif 与中文回退，标题 20–22sp、成员小标题 17sp、正文 14–15sp、帮助 13sp，来源 MotoComTheme。文本允许换行，成员昵称不固定宽度。房间码本页显示/复制，不用于埋点或日志。

## Layout

首页与设置 max520dp，群组页对齐此内容宽度。水平24dp、主要间距12dp、控件至少48dp。主页面沿用 MainScreen 的 ScrollView；群组页只有一个 Compose verticalScroll，带安全区与键盘避让。大字体自然滚动，不固定卡片高度。

## Elevation & Depth

常规区域白底、边框、无阴影，层次靠标题与间距。确认对话框使用原生 Material 3 模态覆盖，不改变页面布局。

## Shapes

共享新区域使用主题18dp圆角；保留现有首页圆形主操作与26dp深色仪表。现有设置20dp历史圆角不为此任务全部迁移。

## Components

`MotoComPanel` 为首页模式卡与群组信息卡的共同容器，消费主题，不复制颜色。Material Button/OutlinedButton/TextButton、OutlinedTextField、Switch 和 AlertDialog 是交互规范所有者；设置现有控件继续复用。当前选择有文字和选中语义，禁用状态有说明，状态文本可由辅助技术读取。

默认、按下、焦点与禁用反馈由原生 Material 管理。待处理操作禁用重复提交并呈现实际状态。取消、返回使用普通文字按钮；危险操作分离并确认。错误留在区域内并允许重试，不清除房间码。

导航由 MainScreen/MainActivity 与 GroupActivity 负责；返回首页只导航。现有矢量资源用于图标，纯图标必须提供中文名称。无新装饰动画，原有首页动画尊重系统禁用动画设置。

## Do's and Don'ts

- 状态和可用动作只由服务快照产生；进入房间不等同于语音全部就绪。
- 显式区分当前房间音频与默认偏好；主按钮文案与 dispatch 一致。
- 不保存房间码，不把页面返回当成退出房间。
- 不将诊断术语放到日常主操作前，不用本地假状态替代服务确认。
