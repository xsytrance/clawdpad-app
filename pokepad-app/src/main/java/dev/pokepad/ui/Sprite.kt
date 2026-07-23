package dev.pokepad.ui

import android.graphics.Bitmap
import dev.pokepad.core.Dex
import dev.pokepad.core.FEATURES
import dev.pokepad.core.Renderer
import dev.pokepad.core.W
import dev.pokepad.core.autoFeatures

/** Turns the engine's 15x15 pixel frames (0xRRGGBB, 0 = empty) into Android
 *  bitmaps for the phone battle screen and the picker. */
object Sprite {
    fun bitmap(px: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(W, W, Bitmap.Config.ARGB_8888)
        val out = IntArray(W * W)
        for (i in px.indices) { val c = px[i]; out[i] = if (c == 0) 0x00000000 else (0xFF000000.toInt() or c) }
        bmp.setPixels(out, 0, W, 0, 0, W, W)
        return bmp
    }

    fun still(dex: Dex, species: String): Bitmap {
        val s = dex.species[species]!!
        val feats = FEATURES[species] ?: autoFeatures(s.types)
        return bitmap(Renderer.render(s.shape, s.types, feats, -1, false, species).px)
    }
}
