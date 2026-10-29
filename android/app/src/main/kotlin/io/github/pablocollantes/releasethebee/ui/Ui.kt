package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * The app's look, in code: colors that follow the DayNight theme, a small type scale,
 * and builders for the few components the screens share. Spacing is on a 4 dp grid.
 */
class Ui(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density + 0.5f).toInt()
    fun dpf(v: Float) = v * density

    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------- color

    val fg: Int
    val fgMuted: Int
    val background: Int

    init {
        val a = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary, android.R.attr.colorBackground),
        )
        fg = a.getColorStateList(0)?.defaultColor ?: if (night) Color.WHITE else Color.BLACK
        fgMuted = a.getColorStateList(1)?.defaultColor ?: alpha(fg, 0.6f)
        background = a.getColor(2, if (night) Color.BLACK else Color.WHITE)
        a.recycle()
    }

    val accent = ACCENT
    val onAccent = 0xFF231C00.toInt()
    /** Accent text that stays readable on the light background. */
    val accentText = if (night) ACCENT else 0xFF8A6D00.toInt()
    val red = if (night) 0xFFFF6B66.toInt() else 0xFFC62828.toInt()
    val green = if (night) 0xFF5FD38A.toInt() else 0xFF2E7D32.toInt()
    val gray = alpha(fg, 0.38f)
    val surface = alpha(fg, if (night) 0.08f else 0.045f)
    val divider = alpha(fg, 0.12f)
    val accentSoft = alpha(ACCENT, if (night) 0.16f else 0.22f)
    val redSoft = alpha(red, 0.12f)

    // ---------------------------------------------------------------- type

    val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val regular: Typeface = Typeface.DEFAULT

    fun text(size: Float, color: Int = fg, bold: Boolean = false, value: CharSequence? = null) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        typeface = if (bold) medium else regular
        if (value != null) text = value
    }

    fun title(value: String) = text(22f, bold = true, value = value).apply {
        letterSpacing = -0.01f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Small caps label above a group. */
    fun sectionHeader(value: String) = text(12f, fgMuted, bold = true, value = value.uppercase()).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(28), 0, dp(8))
    }

    // ---------------------------------------------------------------- shapes

    fun rounded(color: Int, radiusDp: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp.toFloat())
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(content: Drawable? = null, radiusDp: Int? = null): Drawable {
        val mask = if (content != null) null else GradientDrawable().apply {
            setColor(Color.WHITE)
            if (radiusDp != null) cornerRadius = dpf(radiusDp.toFloat())
        }
        return RippleDrawable(ColorStateList.valueOf(alpha(fg, 0.12f)), content, mask)
    }

    fun dot(color: Int, sizeDp: Int = 8) = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Recolor a [dot]. (A tint would keep the alpha of the color it was made with.) */
    fun setDot(dot: View, color: Int) {
        (dot.background as? GradientDrawable)?.setColor(color)
    }

    // ---------------------------------------------------------------- components

    fun icon(res: Int, description: String, tint: Int = fg, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        background = RippleDrawable(ColorStateList.valueOf(alpha(fg, 0.12f)), null, null)   // borderless
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    /** 56 dp bar: optional back arrow, a title, then actions. */
    fun topBar(titleText: String, onBack: (() -> Unit)?, vararg actions: View): Pair<LinearLayout, TextView> {
        val t = title(titleText)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(if (onBack == null) 20 else 4), 0, dp(4), 0)
            if (onBack != null) addView(icon(R.drawable.ic_back, "Back") { onBack() })
            addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (onBack != null) marginStart = dp(4) })
            actions.forEach { addView(it) }
        }
        return bar to t
    }

    /** Filled accent button: the one main action of a group. */
    fun primaryButton(label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        typeface = medium
        setTextColor(onAccent)
        backgroundTintList = ColorStateList.valueOf(ACCENT)
        setOnClickListener { onClick() }
    }

    fun button(label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** Plain text that acts as a button. Quiet on purpose: for secondary actions. */
    fun link(label: String, color: Int = accentText, size: Float = 14f, onClick: () -> Unit) = text(size, color, bold = true, value = label).apply {
        background = ripple(radiusDp = 8)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A small rounded label: kind, status. */
    fun badge(value: String, bg: Int, color: Int) = text(11f, color, bold = true, value = value.uppercase()).apply {
        letterSpacing = 0.06f
        background = rounded(bg, 6)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }

    /** A filter chip: filled when [on]. */
    fun chip(label: String, on: Boolean, onClick: () -> Unit) = text(14f, if (on) onAccent else fg, bold = true, value = label).apply {
        background = ripple(rounded(if (on) accent else 0, 18, if (on) null else divider))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun setChip(chip: TextView, on: Boolean) {
        chip.setTextColor(if (on) onAccent else fg)
        chip.background = ripple(rounded(if (on) accent else 0, 18, if (on) null else divider))
    }

    fun checkbox() = CheckBox(ctx).apply {
        buttonTintList = ColorStateList.valueOf(accent)
    }

    /** A section title with an optional link at the end ("See all"). */
    fun sectionRow(title: String, link: String? = null, onLink: (() -> Unit)? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(20), 0, dp(4))
        addView(text(12f, fgMuted, bold = true, value = title.uppercase()).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (link != null && onLink != null) addView(link(link, size = 13f) { onLink() })
    }

    fun card(color: Int = surface) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        views.forEach { addView(it) }
    }

    fun column(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    fun space(widthDp: Int = 0, heightDp: Int = 0) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
    }

    companion object {
        const val ACCENT = 0xFFF5C518.toInt()

        fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

        /** Background work for the screens: one thread, so requests never pile up in parallel. */
        val io: Executor = Executors.newSingleThreadExecutor { Thread(it, "ui-io").apply { isDaemon = true } }

        /**
         * Draw behind the system bars (Android 15+ always does) with bar icons that suit the
         * theme, and hand the bar sizes to [apply] so each screen pads the right edges.
         */
        fun edgeToEdge(activity: Activity, root: View, apply: (v: View, top: Int, bottom: Int, left: Int, right: Int) -> Unit) {
            val w = activity.window
            w.decorView                 // installs the decor: before it exists there's no insets controller
            @Suppress("DEPRECATION")    // Android 15+ is edge-to-edge anyway; 13 and 14 need asking
            w.setDecorFitsSystemWindows(false)
            val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                apply(v, bars.top, bars.bottom, bars.left, bars.right)
                insets
            }
        }
    }
}

/**
 * Holds one child at no more than [maxWidthPx], centered: lines of text stay readable
 * on the unfolded screen of a foldable or a tablet.
 */
class CappedWidth(ctx: Context, private val maxWidthPx: Int) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            lp.width = if (avail > maxWidthPx) min(avail, maxWidthPx) else ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER_HORIZONTAL
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
