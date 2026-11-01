package be.alex.launcher

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space

class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.refresh(this)

        val root = FrameLayout(this)
        val wall = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        Wallpapers.apply(this, wall)
        root.addView(wall, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(Look.overlay) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(label("Toutes les apps", 26f, bold = true, color = Look.text), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        var first: View? = null
        for (chunk in Apps.launchables(this).chunked(5)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (app in chunk) {
                val tile = label(app.label, 15f, bold = true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), 0, dp(10), 0)
                    background = Look.tile(this@AllAppsActivity)
                    setTextColor(Look.pillText())
                    isClickable = true
                    tvFocus(1.07f, 10f)
                    setOnClickListener { Apps.launch(this@AllAppsActivity, app.pkg) }
                }
                if (first == null) first = tile
                line.addView(tile, LinearLayout.LayoutParams(0, dp(70), 1f).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
            }
            repeat(5 - chunk.size) {
                line.addView(Space(this), LinearLayout.LayoutParams(0, dp(70), 1f).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
            }
            col.addView(line)
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        first?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Foreground.set(this)
    }

    override fun onPause() {
        super.onPause()
        Foreground.clear(this)
    }
}
