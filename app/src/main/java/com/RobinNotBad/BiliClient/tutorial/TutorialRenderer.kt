package com.RobinNotBad.BiliClient.tutorial

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * 把 [TutorialSpan] 列表渲染成带样式的文本。
 *
 * 等价于旧 `TutorialHelper.loadText()`，区别是数据来源从 XML 换成了 DSL，
 * 没指定颜色或解析失败时不挂颜色、跟随主题文字色（旧实现写死白色，亮色主题下看不清）。
 */
object TutorialRenderer {

    fun toSpannable(spans: List<TutorialSpan>): CharSequence {
        val builder = SpannableStringBuilder()
        for (span in spans) {
            val text = span.text ?: continue
            val start = builder.length
            builder.append(text)
            // 纯换行片段不需要挂样式
            if (text == "\n") continue

            // 没指定颜色（或解析失败）就不挂颜色，跟随主题文字色，亮/暗主题都可读
            parseColor(span.color)?.let { color ->
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val styleSpan = when (span.style) {
                TutorialStyle.BOLD -> StyleSpan(Typeface.BOLD)
                TutorialStyle.ITALIC -> StyleSpan(Typeface.ITALIC)
                TutorialStyle.UNDERLINE -> UnderlineSpan()
                TutorialStyle.STRIKE -> StrikethroughSpan()
                TutorialStyle.NORMAL -> null
            }
            if (styleSpan != null) {
                builder.setSpan(styleSpan, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return builder
    }

    private fun parseColor(color: String?): Int? {
        if (color.isNullOrBlank()) return null
        return try {
            Color.parseColor(color)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
