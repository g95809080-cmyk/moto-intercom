package com.kuma.motointercom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Bounds stay local to the Compose host; native scrolling is resolved at draw time. */
internal fun Modifier.guideAnchor(
    target: GuideTarget,
    report: (GuideTarget, androidx.compose.ui.geometry.Rect) -> Unit
): Modifier = onGloballyPositioned { report(target, it.boundsInRoot()) }

internal data class GuideAnchor(val host: View, val bounds: RectF? = null) {
    fun inWindow(): RectF? {
        if (!host.isAttachedToWindow || !host.isShown || host.width == 0) return null
        val location = IntArray(2)
        host.getLocationInWindow(location)
        return RectF(bounds ?: RectF(0f, 0f, host.width.toFloat(), host.height.toFloat())).apply {
            offset(location[0].toFloat(), location[1].toFloat())
        }
    }
}

/** A window-inset-aware sibling above the real UI. Only explicit taps dispatch app intent. */
internal class OnboardingGuide(
    private val root: FrameLayout,
    private val preferences: OnboardingPreferences,
    private val anchor: (GuideTarget) -> GuideAnchor?,
    private val scroll: () -> ScrollView?,
    private val onAction: (GuideTarget) -> Unit,
    private val onTourStarted: () -> Unit
) {
    private val context = root.context
    private val background = root.findViewById<View>(R.id.main_content_column)
    private val originalAccessibility = background.importantForAccessibility
    private val overlay = FrameLayout(context).apply {
        id = R.id.onboarding_overlay
        elevation = dp(24).toFloat()
        isClickable = true
        visibility = View.GONE
    }
    private var phase = preferences.load()
    private var facts: GuideFacts? = null
    private var displayedIntro = false
    private var displayedTarget: GuideTarget? = null
    private var scrim: SpotlightView? = null
    private var tooltip: ScrollView? = null
    private var focusTarget: View? = null
    private var positionedFor: List<Any>? = null
    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        positionSpotlight()
        true
    }

    init {
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
            }
            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                observer = null
            }
        })
    }

    fun update(value: GuideFacts) {
        facts = value
        if (phase == OnboardingPhase.TOUR && value.state is IntercomState.Connected && value.audioReady) {
            finish()
            return
        }
        if (phase == OnboardingPhase.COMPLETE || value.isBusy) {
            hide()
        } else if (phase == OnboardingPhase.INTRO) {
            showIntro()
        } else {
            val target = value.target()
            if (target == null) hide() else showTarget(target)
        }
    }

    fun replay() {
        phase = OnboardingPhase.INTRO
        preferences.save(phase)
        facts?.let(::update)
    }

    /** Back is an explicit opt-out, never an accidental start/accept/disconnect. */
    fun handleBack(): Boolean {
        if (overlay.visibility != View.VISIBLE) return false
        finish()
        return true
    }

    private fun finish() {
        phase = OnboardingPhase.COMPLETE
        preferences.save(phase)
        hide()
    }

    private fun show() {
        overlay.visibility = View.VISIBLE
        background.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun hide() {
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        background.importantForAccessibility = originalAccessibility
        displayedIntro = false
        displayedTarget = null
        scrim = null
        tooltip = null
        focusTarget = null
        positionedFor = null
    }

    private fun showIntro() {
        if (displayedIntro) return
        hide()
        displayedIntro = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.motocom_background))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val heading = text(R.string.guide_intro_title, 25f, bold = true)
        content.addView(heading)
        content.addView(text(R.string.guide_intro_value, 16f).apply { setPadding(0, dp(8), 0, dp(16)) })
        content.addView(ImageView(context).apply {
            id = R.id.onboarding_image
            setImageResource(R.drawable.onboarding_guide)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.guide_image_description)
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(ScrollView(context).apply {
            id = R.id.onboarding_intro_scroll
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(button(R.string.guide_begin, R.id.onboarding_begin) {
            phase = OnboardingPhase.TOUR
            preferences.save(phase)
            hide()
            onTourStarted()
            facts?.let(::update)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(button(R.string.guide_skip, R.id.onboarding_skip, primary = false, action = ::finish))
        overlay.addView(column, FrameLayout.LayoutParams(-1, -1))
        show()
        heading.post { heading.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    private fun showTarget(target: GuideTarget) {
        if (displayedTarget == target && overlay.visibility == View.VISIBLE) return
        hide()
        displayedTarget = target
        val shade = SpotlightView(context)
        scrim = shade
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        // This accessible proxy covers only the measured hole, and calls the same intent.
        focusTarget = View(context).apply {
            id = R.id.onboarding_target
            isFocusable = true
            contentDescription = context.getString(actionLabel(target))
            setOnClickListener { activate(target) }
            visibility = View.INVISIBLE
        }.also { overlay.addView(it, FrameLayout.LayoutParams(1, 1)) }
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            background = rounded(context.getColor(R.color.motocom_surface))
        }
        bubble.addView(text(title(target), 21f, bold = true))
        bubble.addView(text(body(target), 15f).apply { setPadding(0, dp(8), 0, dp(8)) })
        bubble.addView(button(actionLabel(target), R.id.onboarding_action) { activate(target) })
        bubble.addView(button(
            if (target == GuideTarget.SCAN) R.string.guide_finish else R.string.guide_skip,
            R.id.onboarding_skip, primary = false, action = ::finish
        ))
        tooltip = ScrollView(context).apply { addView(bubble) }.also {
            overlay.addView(it, FrameLayout.LayoutParams(-1, -2))
        }
        show()
    }

    private fun activate(target: GuideTarget) {
        // Re-check the latest UI facts; a stale guide must never turn START into STOP.
        if (phase != OnboardingPhase.TOUR || facts?.target() != target ||
            focusTarget?.visibility != View.VISIBLE) return
        if (target == GuideTarget.SCAN) finish()
        onAction(target)
    }

    private fun positionSpotlight() {
        val target = displayedTarget ?: return
        val shade = scrim ?: return
        val bubble = tooltip ?: return
        val hit = focusTarget ?: return
        val currentAnchor = anchor(target)
        var bounds = currentAnchor?.inWindow()
        if (currentAnchor == null || bounds == null || overlay.width == 0 || overlay.height == 0) {
            hit.visibility = View.INVISIBLE
            shade.hole = RectF()
            return
        }
        val scroller = scroll()
        val positionKey = listOf(target, currentAnchor.host, overlay.width, overlay.height, currentAnchor.bounds ?: RectF())
        val viewportOrigin = IntArray(2)
        scroller?.getLocationInWindow(viewportOrigin)
        val outsideViewport = scroller != null && (bounds.top < viewportOrigin[1] ||
            bounds.bottom > viewportOrigin[1] + scroller.height)
        if ((positionedFor != positionKey || outsideViewport) && scroller != null && currentAnchor.bounds != null) {
            // Compose coordinates do not move when its native ScrollView scrolls.
            // Bring the target to the upper third so the tip has space beneath it.
            scroller.scrollTo(0, (currentAnchor.bounds.top - scroller.height / 3f).toInt().coerceAtLeast(0))
            positionedFor = positionKey
            bounds = currentAnchor.inWindow() ?: return
        }
        val location = IntArray(2)
        overlay.getLocationInWindow(location)
        bounds.offset(-location[0].toFloat(), -location[1].toFloat())
        val clipped = RectF(bounds)
        if (scroller != null && currentAnchor.bounds != null) {
            val viewportLocation = IntArray(2)
            scroller.getLocationInWindow(viewportLocation)
            val viewport = RectF((viewportLocation[0] - location[0]).toFloat(),
                (viewportLocation[1] - location[1]).toFloat(),
                (viewportLocation[0] - location[0] + scroller.width).toFloat(),
                (viewportLocation[1] - location[1] + scroller.height).toFloat())
            if (!clipped.intersect(viewport)) {
                hit.visibility = View.INVISIBLE
                shade.hole = RectF()
                return
            }
        }
        if (!clipped.intersect(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())) {
            hit.visibility = View.INVISIBLE
            shade.hole = RectF()
            return
        }
        shade.hole = clipped
        hit.visibility = View.VISIBLE
        place(hit, clipped.left.toInt(), clipped.top.toInt(), clipped.width().toInt(), clipped.height().toInt())
        val margin = dp(12)
        val rightSpace = overlay.width - clipped.right.toInt() - margin * 2
        val leftSpace = clipped.left.toInt() - margin * 2
        if (overlay.width > overlay.height && maxOf(rightSpace, leftSpace) >= dp(240)) {
            val onRight = rightSpace >= leftSpace
            val sideWidth = (if (onRight) rightSpace else leftSpace).coerceAtMost(dp(420))
            val availableHeight = (overlay.height - margin * 2).coerceAtLeast(1)
            bubble.measure(View.MeasureSpec.makeMeasureSpec(sideWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
            place(bubble, if (onRight) clipped.right.toInt() + margin else clipped.left.toInt() - margin - sideWidth,
                (overlay.height - bubble.measuredHeight) / 2, sideWidth, bubble.measuredHeight)
            return
        }
        val width = (overlay.width - margin * 2).coerceAtMost(dp(420)).coerceAtLeast(1)
        val below = overlay.height - clipped.bottom.toInt() - margin * 2
        val above = clipped.top.toInt() - margin * 2
        val useBelow = below >= above
        val available = (if (useBelow) below else above).coerceAtLeast(1)
        bubble.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        val y = if (useBelow) clipped.bottom.toInt() + margin else clipped.top.toInt() - margin - bubble.measuredHeight
        place(bubble, (overlay.width - width) / 2, y.coerceAtLeast(margin), width, bubble.measuredHeight)
    }

    private fun place(view: View, x: Int, y: Int, width: Int, height: Int) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (params.leftMargin == x && params.topMargin == y && params.width == width && params.height == height) return
        params.leftMargin = x
        params.topMargin = y
        params.width = width
        params.height = height
        params.gravity = Gravity.TOP or Gravity.LEFT
        view.layoutParams = params
    }

    private fun text(resource: Int, size: Float, bold: Boolean = false) = TextView(context).apply {
        setText(resource)
        textSize = size
        setTextColor(context.getColor(R.color.motocom_text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(resource: Int, viewId: Int, primary: Boolean = true, action: () -> Unit) = Button(context).apply {
        id = viewId
        setText(resource)
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        setTextColor(context.getColor(R.color.motocom_text_primary))
        background = rounded(context.getColor(if (primary) R.color.motocom_accent_green else R.color.motocom_surface))
        setOnClickListener { action() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun title(target: GuideTarget) = when (target) {
        GuideTarget.PERMISSION, GuideTarget.PERMISSION_SETTINGS -> R.string.guide_permission_title
        GuideTarget.WIFI -> R.string.guide_wifi_title
        GuideTarget.START -> R.string.guide_start_title
        GuideTarget.DISCOVER -> R.string.guide_discover_title
        GuideTarget.SCAN -> R.string.guide_scan_title
    }
    private fun body(target: GuideTarget) = when (target) {
        GuideTarget.PERMISSION -> R.string.guide_permission_body
        GuideTarget.PERMISSION_SETTINGS -> R.string.guide_permission_settings_body
        GuideTarget.WIFI -> R.string.guide_wifi_body
        GuideTarget.START -> R.string.guide_start_body
        GuideTarget.DISCOVER -> R.string.guide_discover_body
        GuideTarget.SCAN -> R.string.guide_scan_body
    }
    private fun actionLabel(target: GuideTarget) = when (target) {
        GuideTarget.PERMISSION -> R.string.home_permission_grant_cta
        GuideTarget.PERMISSION_SETTINGS -> R.string.home_permission_settings_cta
        GuideTarget.WIFI -> R.string.wifi_settings_cta
        GuideTarget.START -> R.string.discover_start
        GuideTarget.DISCOVER -> R.string.nav_discover
        GuideTarget.SCAN -> R.string.discover_rescan_label
    }
}

private class SpotlightView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var hole = RectF()
        set(value) {
            if (field != value) { field = RectF(value); invalidate() }
        }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val highlighted = RectF(hole).apply { inset(-4 * resources.displayMetrics.density, -4 * resources.displayMetrics.density) }
        if (!hole.isEmpty) path.addRoundRect(highlighted, 24f, 24f, Path.Direction.CW)
        paint.color = 0xB3000712.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        if (!hole.isEmpty) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * resources.displayMetrics.density
            paint.color = context.getColor(R.color.motocom_accent_green)
            canvas.drawRoundRect(highlighted, 24f, 24f, paint)
        }
    }
}
