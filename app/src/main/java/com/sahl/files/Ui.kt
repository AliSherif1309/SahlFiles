package com.sahl.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** The Sahl family look: emerald + gold, light/dark, Arabic/English (RTL follows the phone language). */
object C {
    const val GREEN = 0xFF0E6B4E.toInt()
    const val GOLD = 0xFFC9A227.toInt()
    const val RED = 0xFFC0392B.toInt()
    var night = false
    var BG = 0; var CARD = 0; var TEXT = 0; var SUB = 0; var SEL = 0; var LINE = 0

    fun init(a: Activity) {
        night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BG = if (night) 0xFF0B1512.toInt() else 0xFFF6F4EE.toInt()
        CARD = if (night) 0xFF15241F.toInt() else 0xFFFFFFFF.toInt()
        TEXT = if (night) 0xFFEDEBE3.toInt() else 0xFF1B2420.toInt()
        SUB = if (night) 0xFF9BA89F.toInt() else 0xFF6B746F.toInt()
        SEL = if (night) 0xFF1F3B31.toInt() else 0xFFE3F0EA.toInt()
        LINE = if (night) 0xFF22332D.toInt() else 0xFFE8E4DA.toInt()
        a.window.statusBarColor = BG
        a.window.navigationBarColor = BG
        a.window.decorView.setBackgroundColor(BG)
        val light = if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        a.window.insetsController?.setSystemBarsAppearance(light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
}

val isAr: Boolean get() = Locale.getDefault().language == "ar"
fun t(en: String, ar: String) = if (isAr) ar else en

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.round(color: Int, radius: Int = 16, stroke: Int = 0) = GradientDrawable().apply {
    cornerRadius = dp(radius).toFloat(); setColor(color)
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.ripple(bg: android.graphics.drawable.Drawable?) =
    RippleDrawable(ColorStateList.valueOf(0x3320A070), bg, round(Color.WHITE))

fun Context.button(s: String, primary: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
    text = s; textSize = 15f; gravity = Gravity.CENTER
    setTextColor(if (primary) Color.WHITE else C.GREEN)
    setPadding(dp(18), dp(11), dp(18), dp(11))
    background = ripple(if (primary) round(C.GREEN, 24) else round(Color.TRANSPARENT, 24, C.GREEN))
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.cardBox() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = round(C.CARD, 18)
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.LayoutParams.margins(c: Context, l: Int = 0, tp: Int = 0, r: Int = 0, b: Int = 0) =
    apply { setMargins(c.dp(l), c.dp(tp), c.dp(r), c.dp(b)) }

/** Title row with an optional back arrow and small icon actions. */
fun Activity.topBar(title: String, back: Boolean, vararg actions: Pair<String, () -> Unit>) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(8), dp(10), dp(8), dp(6))
    if (back) addView(iconBtn(if (isAr) "→" else "←") { onBackPressed() })
    addView(label(title, 22f, C.TEXT, true).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WRAP_CONTENT, 1f))
    for ((icon, run) in actions) addView(iconBtn(icon, run))
}

fun Context.iconBtn(icon: String, run: () -> Unit) = TextView(this).apply {
    text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(C.TEXT)
    background = ripple(null)
    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
    setOnClickListener { run() }
}

fun Activity.dialog(): AlertDialog.Builder =
    AlertDialog.Builder(this, if (C.night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

fun Activity.ask(title: String, initial: String = "", password: Boolean = false, numeric: Boolean = false, onOk: (String) -> Unit) {
    val e = EditText(this).apply {
        setText(initial); setSelection(initial.length)
        inputType = when {
            numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
    }
    val box = LinearLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(e, lp()) }
    dialog().setTitle(title).setView(box)
        .setPositiveButton(t("OK", "موافق")) { _, _ -> val v = e.text.toString().trim(); if (v.isNotEmpty()) onOk(v) }
        .setNegativeButton(t("Cancel", "إلغاء"), null).show()
}

fun Activity.confirm(title: String, msg: String, ok: String, run: () -> Unit) {
    dialog().setTitle(title).setMessage(msg).setPositiveButton(ok) { _, _ -> run() }
        .setNegativeButton(t("Cancel", "إلغاء"), null).show()
}

/** A small progress window. Returns (dialog, set(text, percent 0..100 or -1)). */
fun Activity.progress(title: String): Pair<AlertDialog, (String, Int) -> Unit> {
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
    val msg = label("", 13f, C.SUB).apply { maxLines = 2 }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(4))
        addView(bar, lp()); addView(msg, lp())
    }
    val d = dialog().setTitle(title).setView(box).setCancelable(false).create()
    d.show()
    return d to { text: String, p: Int ->
        runOnUiThread {
            msg.text = text
            if (p < 0) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = p }
        }
    }
}

fun Long.human(): String {
    if (this < 1024) return "$this B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = this / 1024.0; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, u[i])
}

fun View.gone(g: Boolean) { visibility = if (g) View.GONE else View.VISIBLE }
