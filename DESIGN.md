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

## 首页单屏与主动声纳

首页采用用户在 2026-11-12 确认的白昼绿单屏预览：左侧品牌、右侧运行状态；中间声纳使用剩余空间；操作台、紧凑云端入口和底部导航始终可见。首页不使用纵向滚动容器，切换待机、搜索和连接状态不追加独立的大按钮。横屏时声纳在左、操作区在右；小屏和大字体优先压缩声纳，保留至少 48dp 的主要点击区域。

权限、Wi-Fi 和服务状态集中在声纳底部的状态位，保留必要操作；长文通过“详情”查看。发现页与设置页继续保留各自原有滚动行为。

主动声纳只将实际发现且可连接的摩友、或当前通话对象作为回波目标。空列表只显示声源激发与向外压力波，禁止添加虚拟反射点。波阵面接触目标迎波面后才出现反向散射，目标后方产生声影；目标移除时停止该目标的回波和声影。首页和发现页共用 `ActiveSonar.kt` 的时间与绘制逻辑；节点距离和方位为示意排布，不作为实际测距。

验收覆盖空列表、有摩友、摩友离开、权限异常、横竖屏和大字体；检查内容及主要控件完整位于首页视口内，滑动不改变首页位置。
