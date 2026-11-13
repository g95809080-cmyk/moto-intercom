# KUM-46 local emulator evidence

Date: 2026-08-04  
Target: `MotoIntercom_API_36` / `emulator-5554` / Android API 36  
Scope: emulator only; no physical Android device was controlled in this run.

## Captured and verified from UI tree

- Cold launch resolves `com.kuma.motointercom/.SplashActivity` and reaches `MainActivity`.
- Home, navigation panel, Discover, Settings, Logs, Discover/Settings Back, and system Back from Settings were captured.
- Home mute and Discover help both open the shared `功能开发中` dialog; the dialog exposes the exact title, message, and `确定` action. Confirming returns to the original route.
- Home real state path reached `正在寻找附近 MotoCom 车友` / `结束对讲`, then returned to `点击下方启动摩声` / `对讲已结束` after the real Stop action.
- Crash buffer after the run is empty.

Relevant UI-tree artifacts are `01-home.xml`, `02-nav-panel.xml`, `03-discover.xml`, `04-placeholder-dialog.xml`, `07-settings.xml`, `08-settings-scrolled.xml`, `09-logs.xml`, `11-home-after-settings-back.xml`, `12-home-mute-placeholder.xml`, `14-start-permission.xml`, `15-home-discovering.xml`, and `16-home-after-stop.xml`.

## Responsive UI-tree matrix

`responsive/` contains 320dp, 360dp, 411dp, 600dp, landscape 800dp, and 360dp at font scale 1.3. The deterministic checker reports one vertical scroll container and `overflow=0` for every captured XML. The 600dp profile keeps the card content at the centered 520dp maximum; the 1.3x profile grows text/control bounds without horizontal overflow.

## Visual-capture limitation

The emulator UI tree is populated and interactive, but `screencap` returns a uniform dark frame (`YMIN=YAVG=YMAX=16`) even after switching GPU backends and using a visible emulator window. The PNGs are retained as capture artifacts, but are not counted as visual/design-pass evidence. Rounded/cutout/three-button navigation and TalkBack remain Not Run.
