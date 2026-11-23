package com.vexa.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object UiUtil {
    fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    fun pillBg(ctx: Context, fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(ctx, 10).toFloat()
            setStroke(dp(ctx, 1), Color.parseColor(strokeHex))
        }
    }
}
