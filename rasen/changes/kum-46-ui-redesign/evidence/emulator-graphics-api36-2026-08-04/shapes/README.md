# Graphical AVD system-shape follow-up

Date: 2026-08-04  
Target: `MotoIntercom_Graphics_API36` / `emulator-5556` / Android API 36  
Scope: system navigation and display-cutout overlays only; no physical device was controlled.

## Profiles captured

| Profile | Overlay configuration | Evidence | Result |
| --- | --- | --- | --- |
| Rounded corner + gesture navigation | `com.android.internal.display.cutout.emulation.corner` + `com.android.internal.systemui.navbar.gestural` | `gesture-cutout-home.xml`, `gesture-cutout-home.png`, `gesture-cutout-crash.txt` | UI remains inside safe bounds; gesture handle is visible; crash buffer empty |
| Hole cutout + gesture navigation | `com.android.internal.display.cutout.emulation.hole` + `com.android.internal.systemui.navbar.gestural` | `gesture-hole-home.xml`, `gesture-hole-home.png`, `gesture-hole-crash.txt` | UI remains inside safe bounds; gesture handle is visible; crash buffer empty |
| 360dp hole cutout + gesture navigation | `wm size 945x2400` + hole + gestural overlays | `360dp-hole-gesture-home.xml`, `360dp-hole-gesture-home.png`, `360dp-hole-gesture-crash.txt` | Wrapped long labels remain inside the vertical page; no horizontal clipping; crash buffer empty |
| 600dp rounded corner + gesture navigation | `wm size 1575x2400` + corner + gestural overlays | `600dp-corner-gesture-home.xml`, `600dp-corner-gesture-home.png`, `600dp-corner-gesture-crash.txt` | Content remains centered within the 520dp cap; gesture handle visible; crash buffer empty |
| 320dp hole cutout + gesture navigation | `wm size 840x2400` + hole + gestural overlays | `320dp-hole-gesture-home.xml`, `320dp-hole-gesture-home.png`, `320dp-hole-gesture-crash.txt` | Compact labels wrap without horizontal clipping; gesture handle visible; crash buffer empty |
| 411dp rounded corner + gesture navigation | `wm size 1080x2400` + corner + gestural overlays | `411dp-corner-gesture-home.xml`, `411dp-corner-gesture-home.png`, `411dp-corner-gesture-crash.txt` | Standard-width content remains inside the safe host; gesture handle visible; crash buffer empty |
| Landscape rounded corner + gesture navigation | `wm size 2100x1080` + corner + gestural overlays | `landscape-corner-gesture-home.xml`, `landscape-corner-gesture-home.png`, `landscape-corner-gesture-crash.txt` | Landscape page remains reachable through vertical scroll; gesture handle visible; crash buffer empty |
| 360dp at 1.3x font, hole cutout + gesture navigation | `wm size 945x2400`, `font_scale=1.3` + hole + gestural overlays | `360dp-font13-hole-gesture-home.xml`, `360dp-font13-hole-gesture-home.png`, `360dp-font13-hole-gesture-crash.txt` | Enlarged labels remain reachable without horizontal clipping; crash buffer empty |

`shape-responsive-check.txt` reports one vertical scroll container and `overflow=0` for every captured shape/size profile. The AVD was restored after capture to the normal three-button overlay with the corner profile enabled. The screenshots exercise the real XML layout and WindowInsets path; they do not claim product-state, Presence, incoming-request, TalkBack, or two-device behavior.
