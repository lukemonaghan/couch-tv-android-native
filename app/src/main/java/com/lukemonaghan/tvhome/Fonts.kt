package com.lukemonaghan.tvhome

import android.content.Context
import android.graphics.Typeface

/** The two typefaces couch-tv uses: Inter for text (static instances at 450, 500 and 600) and Antonio Light for the clock. */
object Fonts {
    private val cache = HashMap<String, Typeface>()
    private fun load(ctx: Context, file: String, fallback: Typeface): Typeface =
        cache.getOrPut(file) { runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$file.ttf") }.getOrDefault(fallback) }

    fun regular(ctx: Context) = load(ctx, "inter_regular", Typeface.DEFAULT)
    fun medium(ctx: Context) = load(ctx, "inter_medium", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun semibold(ctx: Context) = load(ctx, "inter_semibold", Typeface.create("sans-serif-medium", Typeface.BOLD))
    fun antonio(ctx: Context) = load(ctx, "antonio_light", Typeface.create("sans-serif-condensed-light", Typeface.NORMAL))
}
