---
version: alpha
name: "摩声 MotoCom"
description: "Android 双人骑行对讲，沿用现有轻绿设置卡片与原生 Compose 控件。"
colors:
  primary: "#416722"
  accent: "#C3F36B"
  background: "#F3F4EF"
  surface: "#FFFFFF"
  text: "#18201D"
typography:
  sans:
    fontFamily: "Android system sans-serif"
rounded:
  DEFAULT: "18dp"
spacing:
  page-horizontal: "24dp"
  card-padding: "18dp"
  control-min-height: "48dp"
components:
  switch: {}
  card: {}
---

# 摩声设置界面

现有 Android 产品界面保持熟悉、易读。目标用户在手机上准备骑行对讲；本次只增加降噪对照设置，简体中文，沿用系统中文字体。没有日本市场或新品牌设计需求。

## Runtime mapping

现有运行时资源保持唯一token权威：`app/src/main/res/values/colors.xml`、`dimens.xml` → `MotoComTheme.kt` → `SettingsScreen.kt`。本文件镜像现有值；不生成CSS或引入第二套主题。

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Form | SettingsSwitchRow / SettingsPanel | SettingsScreen.kt + KUM-88 spec | 常规设置、仅Offline可改的Beta降噪开关 | Android UI与持久化检查 |

## Components and behavior

复用现有 `SettingsSwitchRow`，行和Switch共享选择语义；新增enabled参数，在线时行与Switch均禁用。说明直接出现在卡片内，指出停止后可切换、下次启动生效。当前选择用文字展示。没有下拉弹窗、额外权限或保存确认。

颜色、圆角、字体和布局节奏沿用设置中的VOX及自动重连控件；使用现有垂直滚动容器，48dp以上点击区域，状态不只依赖颜色。用户选择即时落盘，保存失败使用已有提示；在线source的降噪模式保持固定。业务来源为 `rasen/changes/kum-88-rnnoise-beta/specs/rnnoise-beta/spec.md`。

## Verification

Android原生项目：Compose语义/ADB界面、APK启动及相关仪器测试是运行时证据。网页静态审计不替代这些检查。真实骑行音质验收另列。
