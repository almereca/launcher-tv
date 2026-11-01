package be.alex.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun focusColors(focused: Int, normal: Int): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
    intArrayOf(focused, normal)
)

/** Petit zoom + élévation au focus, façon tvOS. */
fun View.tvFocus(scale: Float = 1.06f, lift: Float = 12f, extra: ((Boolean) -> Unit)? = null) {
    isFocusable = true
    setOnFocusChangeListener { v, has ->
        v.animate()
            .scaleX(if (has) scale else 1f)
            .scaleY(if (has) scale else 1f)
            .translationZ(if (has) v.context.dpf(lift) else 0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
        extra?.invoke(has)
    }
}

fun Context.label(text: String, sizeSp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

class CardSpec(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val star: Boolean,
    val progress: Float?
)

/** Carte 16:9 de la rangée À suivre. */
fun Context.card(spec: CardSpec, wDp: Int = 176, hDp: Int = 99): LinearLayout {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        id = View.generateViewId()
        contentDescription = spec.title
    }

    val box = FrameLayout(this).apply {
        background = Look.cardImage(this@card)
        foreground = Look.cardFocus(this@card)
        clipToOutline = true
        isDuplicateParentStateEnabled = true
    }

    if (spec.image != null) {
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        iv.load(spec.image) { crossfade(true) }
        box.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    } else {
        val t = label(spec.title, 13f, bold = true, color = Look.text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        box.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    if (spec.star) {
        val star = ImageView(this).apply {
            setImageResource(R.drawable.ic_star)
            imageTintList = Look.solid(if (spec.image != null) Color.WHITE else Look.text)
        }
        box.addView(star, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); marginEnd = dp(8)
        })
    }

    val p = spec.progress
    if (p != null && p > 0.02f) {
        val track = FrameLayout(this).apply { setBackgroundColor(0x59FFFFFF) }
        val fill = View(this).apply { setBackgroundColor(Color.WHITE) }
        track.addView(fill, FrameLayout.LayoutParams((dp(wDp - 20) * p).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(track, FrameLayout.LayoutParams(dp(wDp - 20), dp(3), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(10); bottomMargin = dp(8)
        })
    }

    root.addView(box, LinearLayout.LayoutParams(dp(wDp), dp(hDp)))

    val title = label(spec.title, 14f, bold = true, color = Look.text).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    root.addView(title, LinearLayout.LayoutParams(dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    if (!spec.subtitle.isNullOrBlank()) {
        val sub = label(spec.subtitle, 12f, color = Look.text2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(sub, LinearLayout.LayoutParams(dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    root.pivotY = dpf(hDp / 2f)
    root.tvFocus(1.1f, 16f)
    return root
}
