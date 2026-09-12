package com.kuma.motointercom

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class OnboardingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = OnboardingPreferences(context)
    private val runtime = RuntimeSessionId("guide-runtime")

    @Before fun resetPreferences() {
        context.getSharedPreferences("motocom_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun unfinishedProgressAndDeniedPermissionSurviveNewInstance() {
        assertEquals(OnboardingPhase.INTRO, preferences.load())
        preferences.save(OnboardingPhase.TOUR)
        preferences.permissionRequested = true
        assertEquals(OnboardingPhase.TOUR, preferences.load())
        assertTrue(preferences.permissionRequested)
        assertFalse(context.getSharedPreferences("motocom_onboarding", 0).getBoolean("hasShownGuide", false))
        preferences.save(OnboardingPhase.COMPLETE)
        assertEquals(OnboardingPhase.COMPLETE, preferences.load())
        assertTrue(context.getSharedPreferences("motocom_onboarding", 0).getBoolean("hasShownGuide", false))
        preferences.save(OnboardingPhase.INTRO)
        assertEquals(OnboardingPhase.INTRO, preferences.load())
    }

    @Test fun tutorialFollowsRealPreconditionsAndYieldsToIncomingConfirmation() {
        val initial = GuideFacts(IntercomState.Offline, MainRoute.HOME, false, false, true, false, false)
        assertEquals(GuideTarget.PERMISSION, initial.target())
        assertEquals(GuideTarget.PERMISSION_SETTINGS, initial.copy(permissionRequested = true).target())
        assertEquals(GuideTarget.WIFI, initial.copy(canStart = true).target())
        assertEquals(GuideTarget.START, initial.copy(canStart = true, wifiUnavailable = false).target())
        val online = initial.copy(state = IntercomState.Discovering(runtime), canStart = true, wifiUnavailable = false)
        assertEquals(GuideTarget.DISCOVER, online.target())
        assertEquals(GuideTarget.SCAN, online.copy(route = MainRoute.DISCOVER).target())
        assertNull(online.copy(incomingConfirmation = true).target())
        assertNull(online.copy(route = MainRoute.SETTINGS).target())
        assertNull(online.copy(route = MainRoute.DISCOVER, wifiUnavailable = true).target())
    }

    @Test fun introDoesNotStartServiceAndSkipPersistsAcrossRecreation() {
        var starts = 0
        val fixture = fixture(onStart = { starts++ })
        val root = fixture.second.root
        assertNotNull(root.findViewById<View>(R.id.onboarding_image))
        assertEquals(0, starts)
        root.findViewById<View>(R.id.onboarding_skip).performClick()
        assertEquals(OnboardingPhase.COMPLETE, preferences.load())
        assertEquals(View.GONE, root.findViewById<View>(R.id.onboarding_overlay).visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, root.findViewById<View>(R.id.main_content_column).importantForAccessibility)
        val saved = Bundle().also(fixture.second::saveState)
        fixture.first.pause().stop().destroy()
        val next = fixture(saved = saved)
        assertEquals(View.GONE, next.second.root.findViewById<View>(R.id.onboarding_overlay).visibility)
    }

    @Test fun realStartWaitsForServiceThenFinishesWithoutAnotherPhone() {
        var starts = 0
        var scans = 0
        val fixture = fixture(onStart = { starts++ }, onScan = { scans++ })
        val screen = fixture.second
        screen.setIntercomState(IntercomState.Offline, true)
        screen.root.findViewById<View>(R.id.onboarding_begin).performClick()
        settle(screen)
        val startButton = screen.root.findViewById<Button>(R.id.onboarding_action)
        assertEquals("启动摩声", startButton.text.toString())
        startButton.performClick()
        assertEquals(1, starts)
        assertEquals("启动摩声", screen.root.findViewById<Button>(R.id.onboarding_action).text.toString())
        assertEquals(OnboardingPhase.TOUR, preferences.load())
        screen.setIntercomState(IntercomState.Discovering(runtime), true)
        settle(screen)
        // Stale START callbacks cannot disconnect a newly started runtime.
        startButton.performClick()
        assertEquals(1, starts)
        screen.root.findViewById<View>(R.id.onboarding_action).performClick()
        settle(screen)
        assertNotNull(screen.root.findViewById<ScrollView>(R.id.discover_scroll))
        assertEquals("重新扫描", screen.root.findViewById<Button>(R.id.onboarding_action).text.toString())
        screen.root.findViewById<View>(R.id.onboarding_action).performClick()
        assertEquals(1, scans)
        assertEquals(OnboardingPhase.COMPLETE, preferences.load())
    }

    @Test fun permissionGuideAnchorsToActualStartButtonAndRestoresSettingsAfterDenial() {
        var requests = 0
        val fixture = fixture(onPermission = { requests++ })
        val screen = fixture.second
        screen.root.findViewById<View>(R.id.onboarding_begin).performClick()
        settle(screen)
        assertEquals(View.VISIBLE, screen.root.findViewById<View>(R.id.onboarding_target).visibility)
        screen.root.findViewById<View>(R.id.onboarding_action).performClick()
        assertEquals(1, requests)
        screen.markPermissionRequestAttempted()
        screen.setIntercomState(IntercomState.Offline, false)
        settle(screen)
        assertEquals("打开系统权限设置", screen.root.findViewById<Button>(R.id.onboarding_action).text.toString())
        fixture.first.pause().stop().destroy()
        val next = fixture().second
        settle(next)
        assertNull(next.root.findViewById<View>(R.id.onboarding_image))
        assertTrue(next.permissionRequestWasAttempted())
        assertEquals("打开系统权限设置", next.root.findViewById<Button>(R.id.onboarding_action).text.toString())
    }

    @Test fun incomingDialogHidesGuideWithoutCompletingIt() {
        val fixture = fixture()
        fixture.second.root.findViewById<View>(R.id.onboarding_begin).performClick()
        fixture.second.setIncomingConfirmationVisible(true)
        assertEquals(View.GONE, fixture.second.root.findViewById<View>(R.id.onboarding_overlay).visibility)
        assertEquals(OnboardingPhase.TOUR, preferences.load())
        fixture.second.setIncomingConfirmationVisible(false)
        assertEquals(View.VISIBLE, fixture.second.root.findViewById<View>(R.id.onboarding_overlay).visibility)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp")
    fun smallLandscapeRevealsAnchorAgainAfterNativeScrollRestoration() {
        preferences.save(OnboardingPhase.TOUR)
        val screen = fixture().second
        screen.setIntercomState(IntercomState.Offline, true)
        settle(screen)
        val scroll = screen.root.findViewById<ScrollView>(R.id.home_scroll)
        scroll.scrollTo(0, 0)
        settle(screen)
        val target = screen.root.findViewById<View>(R.id.onboarding_target)
        assertEquals(View.VISIBLE, target.visibility)
        val targetOrigin = IntArray(2).also(target::getLocationInWindow)
        val viewportOrigin = IntArray(2).also(scroll::getLocationInWindow)
        assertTrue(target.height > 0)
        assertTrue(targetOrigin[1] >= viewportOrigin[1])
        assertTrue(targetOrigin[1] + target.height <= viewportOrigin[1] + scroll.height)
        assertTrue(screen.handleBack())
        assertEquals(OnboardingPhase.COMPLETE, preferences.load())
    }

    @Test fun channelWithoutAudioIsNotClaimedAsSuccessfulFirstExperience() {
        val attempt = ConnectionAttempt(ConnectionAttemptId("guide-attempt"), runtime,
            TargetLock("peer", RuntimeSessionId("peer-runtime")), ConnectionTrigger.USER,
            ChannelPlan.single(Transport.LAN), 1_000L)
        val peer = PeerIdentity("peer", "车友", "Pixel", RuntimeSessionId("peer-runtime"), true)
        val state = IntercomState.Connected(attempt, peer, 1L, Transport.LAN)
        val waiting = homePresentation(state, true, "", false, audioReady = false)
        assertEquals("正在等待音频就绪", waiting.primaryText)
        assertTrue(waiting.webRtcText.contains("待音频"))
        assertEquals("语音通道已连接", homePresentation(state, true, "", false, audioReady = true).primaryText)
        val fixture = fixture()
        fixture.second.root.findViewById<View>(R.id.onboarding_begin).performClick()
        fixture.second.setIntercomState(state, true)
        assertEquals(OnboardingPhase.TOUR, preferences.load())
        assertEquals(View.GONE, fixture.second.root.findViewById<View>(R.id.onboarding_overlay).visibility)
        fixture.second.setAudioReady(true)
        assertEquals(OnboardingPhase.COMPLETE, preferences.load())
    }

    private fun fixture(saved: Bundle? = null, onStart: () -> Unit = {}, onScan: () -> Unit = {}, onPermission: () -> Unit = {}): Pair<org.robolectric.android.controller.ActivityController<ComponentActivity>, MainScreen> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val screen = MainScreen(activity, "", saved, onStart, { false }, { true }, onPermission,
            {}, {}, {}, onRequestDiscoveryRefresh = onScan, onboardingPreferences = preferences)
        activity.setContentView(screen.root)
        return controller to screen
    }

    private fun settle(screen: MainScreen) {
        compose.waitForIdle()
        val density = context.resources.displayMetrics.density
        repeat(3) {
            screen.root.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY))
            screen.root.layout(0, 0, screen.root.measuredWidth, screen.root.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            screen.root.viewTreeObserver.dispatchOnPreDraw()
        }
        compose.waitForIdle()
    }
}
