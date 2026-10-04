package com.example.bydbattery

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val BG = 0xFF121417.toInt()
    val CARD = 0xFF1E2227.toInt()
    val TILE = 0xFF262B31.toInt()
    val TAB_BG = 0xFF181B1F.toInt()
    val TEXT = 0xFFEDEFF2.toInt()
    val SUB = 0xFF9AA3AD.toInt()
    val ACCENT = 0xFF4CAF50.toInt()
    val WARN = 0xFFFFB300.toInt()
    val BAD = 0xFFE5534B.toInt()
    val BTN = 0xFF2F3640.toInt()
}

class Tile(val root: LinearLayout, val value: TextView, val raw: TextView)

/** Небольшой набор функций для построения интерфейса кодом. */
class UiKit(private val ctx: Context) {

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun text(s: String, sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun mono(sizeSp: Float = 11f) = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = sizeSp
        setTextColor(Palette.TEXT)
        setTextIsSelectable(true)
    }

    fun card(title: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.CARD)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        if (title != null) {
            addView(text(title, 15f, Palette.TEXT, true).apply { setPadding(0, 0, 0, dp(8)) })
        }
    }

    fun note(s: String) = text(s, 12f, Palette.SUB).apply { setPadding(0, 0, 0, dp(8)) }

    fun tile(title: String): Tile {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.TILE, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val t = text(title, 11f, Palette.SUB)
        val v = text("—", 19f, Palette.TEXT, true)
        val r = text("", 9f, Palette.SUB).apply { typeface = Typeface.MONOSPACE }
        box.addView(t); box.addView(v); box.addView(r)
        return Tile(box, v, r)
    }

    /** Ряд из нескольких view равной ширины. */
    fun row(vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) 0xFF0E1A10.toInt() else Palette.TEXT)
        background = rounded(if (primary) Palette.ACCENT else Palette.BTN, 10)
        minHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    fun spacer(hDp: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(hDp)) }

    fun tabLabel(s: String) = text(s, 13f, Palette.SUB).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
    }
}
