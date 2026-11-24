package com.backrooms.game
// SkinPreview.kt - draws one skin on the block model into a small ARGB buffer (menus: Dressing Room, main menu button, pause menu).
// Shared by desktop and Android (pure Kotlin/JVM).

object SkinPreview {
    /** Renders [tex] on a figure that turns slowly: [t] is time in seconds. [out] must hold w * h ints; it is cleared to transparent first. */
    fun render(tex: SkinTexture, w: Int, h: Int, t: Double, out: IntArray) {
        require(w > 0 && h > 0 && out.size >= w * h)
        java.util.Arrays.fill(out, 0, w * h, 0)
        val fov = Math.toRadians(38.0); val focal = w / (2.0 * Math.tan(fov / 2))
        val worldH = 1.8; val scale = worldH / 1.52
        val dist = worldH * focal / (h * 0.84) // the figure fills ~84% of the height
        val yaw = Math.PI + t * 0.9           // PI = facing the camera
        val pivot = 0.08 * scale              // the model's centre is a little in front of its origin: keep the turn centred
        val depth = DoubleArray(w) { 1e9 }
        PlayerSkinRenderer.draw(out, w, h, depth, tex, dist - Math.cos(yaw) * pivot, -Math.sin(yaw) * pivot, yaw,
            0.0, 0.0, 0.0, fov, (h * 0.97).toInt(), worldH, { 1.0 })
    }
}
