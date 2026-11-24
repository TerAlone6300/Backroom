package com.backrooms.game

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Canonical 64x64 RGBA pixels decoded from a supported player-skin PNG. */
class SkinTexture private constructor(val pixels: IntArray) {
    fun sample(u: Int, v: Int): Int = pixels[v.coerceIn(0, 63) * SIZE + u.coerceIn(0, 63)]

    companion object {
        const val SIZE = 64
        private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private const val MAX_PNG_BYTES = 8 * 1024 * 1024

        /** Reads a 32x32, 64x64, or 128x128 8-bit, non-interlaced RGB/RGBA/indexed PNG. */
        fun fromPng(bytes: ByteArray): SkinTexture {
            require(bytes.size in 33..MAX_PNG_BYTES) { "PNG size is invalid" }
            require(signature.indices.all { bytes[it] == signature[it] }) { "Skin must be a PNG" }
            val input = DataInputStream(ByteArrayInputStream(bytes, signature.size, bytes.size - signature.size))
            var width = 0; var height = 0; var depth = 0; var colorType = -1; var interlace = -1
            var palette = ByteArray(0); var transparency = ByteArray(0)
            val idat = ByteArrayOutputStream()
            var ended = false
            while (!ended && input.available() >= 12) {
                val length = input.readInt()
                require(length >= 0 && length <= input.available() - 8) { "PNG chunk is truncated" }
                val typeBytes = ByteArray(4); input.readFully(typeBytes)
                val data = ByteArray(length); input.readFully(data)
                val expectedCrc = input.readInt().toLong() and 0xffffffffL
                val crc = CRC32().apply { update(typeBytes); update(data) }.value
                require(expectedCrc == crc) { "PNG checksum mismatch" }
                when (String(typeBytes, Charsets.US_ASCII)) {
                    "IHDR" -> {
                        require(length == 13 && width == 0) { "Invalid PNG header" }
                        DataInputStream(ByteArrayInputStream(data)).use { h ->
                            width = h.readInt(); height = h.readInt(); depth = h.readUnsignedByte()
                            colorType = h.readUnsignedByte(); h.readUnsignedByte(); h.readUnsignedByte(); interlace = h.readUnsignedByte()
                        }
                    }
                    "PLTE" -> palette = data
                    "tRNS" -> transparency = data
                    "IDAT" -> {
                        require(idat.size().toLong() + length <= MAX_PNG_BYTES) { "PNG image data is too large" }
                        idat.write(data)
                    }
                    "IEND" -> ended = true
                }
            }
            require(ended) { "PNG has no end chunk" }
            require(width == height && width in setOf(32, 64, 128)) { "Skin dimensions must be 32x32, 64x64, or 128x128" }
            require(depth == 8 && interlace == 0) { "Skin PNG must use 8-bit non-interlaced pixels" }
            val channels = when (colorType) { 2 -> 3; 3 -> 1; 6 -> 4; else -> error("Skin PNG must be RGB, RGBA, or indexed") }
            require(colorType != 3 || palette.isNotEmpty() && palette.size % 3 == 0 && palette.size <= 768) { "Invalid PNG palette" }
            val stride = Math.multiplyExact(width, channels)
            val rawSize = Math.multiplyExact(height, Math.addExact(stride, 1))
            val raw = ByteArray(rawSize)
            InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { inflater ->
                var offset = 0
                while (offset < raw.size) {
                    val count = inflater.read(raw, offset, raw.size - offset)
                    if (count < 0) break
                    offset += count
                }
                require(offset == raw.size && inflater.read() == -1) { "PNG pixel data length is invalid" }
            }
            val decoded = ByteArray(Math.multiplyExact(height, stride))
            for (row in 0 until height) {
                val src = row * (stride + 1); val dst = row * stride
                val filter = raw[src].toInt() and 255
                require(filter in 0..4) { "Unknown PNG filter" }
                for (column in 0 until stride) {
                    val value = raw[src + 1 + column].toInt() and 255
                    val left = if (column >= channels) decoded[dst + column - channels].toInt() and 255 else 0
                    val up = if (row > 0) decoded[dst + column - stride].toInt() and 255 else 0
                    val upperLeft = if (row > 0 && column >= channels) decoded[dst + column - stride - channels].toInt() and 255 else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) ushr 1
                        else -> paeth(left, up, upperLeft)
                    }
                    decoded[dst + column] = (value + predictor).toByte()
                }
            }
            val source = IntArray(width * height)
            for (i in source.indices) {
                val offset = i * channels
                val r: Int; val g: Int; val b: Int; val a: Int
                when (colorType) {
                    2 -> { r = decoded[offset].toInt() and 255; g = decoded[offset + 1].toInt() and 255; b = decoded[offset + 2].toInt() and 255; a = 255 }
                    6 -> { r = decoded[offset].toInt() and 255; g = decoded[offset + 1].toInt() and 255; b = decoded[offset + 2].toInt() and 255; a = decoded[offset + 3].toInt() and 255 }
                    else -> {
                        val index = decoded[offset].toInt() and 255
                        val p = index * 3
                        require(p + 2 < palette.size) { "PNG palette index is out of range" }
                        r = palette[p].toInt() and 255; g = palette[p + 1].toInt() and 255; b = palette[p + 2].toInt() and 255
                        a = transparency.getOrNull(index)?.toInt()?.and(255) ?: 255
                    }
                }
                source[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            // Convert supported resolutions to the same 64-pixel UV space, preserving crisp texels.
            val canonical = IntArray(SIZE * SIZE)
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                canonical[y * SIZE + x] = source[(y * height / SIZE) * width + (x * width / SIZE)]
            }
            return SkinTexture(canonical)
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}

/** UV rectangle for a cuboid face in the canonical 64x64 skin atlas. */
data class SkinFace(val u: Int, val v: Int, val width: Int, val height: Int)

data class SkinCuboid(val faces: List<SkinFace>) { init { require(faces.size == 6) } }

/** Face order: front, right, back, left, top, bottom. */
object PlayerSkinLayout {
    private fun box(x: Int, y: Int, w: Int, h: Int, d: Int) = SkinCuboid(listOf(
        SkinFace(x + d, y + d, w, h), SkinFace(x, y + d, d, h),
        SkinFace(x + d + w + d, y + d, w, h), SkinFace(x + d + w, y + d, d, h),
        SkinFace(x + d, y, w, d), SkinFace(x + d + w, y, w, d)
    ))

    val head = box(0, 0, 8, 8, 8)
    val headOverlay = box(32, 0, 8, 8, 8)
    val torso = box(16, 16, 8, 12, 4)
    val torsoOverlay = box(16, 32, 8, 12, 4)
    val rightArm = box(40, 16, 4, 12, 4)
    val rightArmOverlay = box(40, 32, 4, 12, 4)
    val leftArm = box(32, 48, 4, 12, 4)
    val leftArmOverlay = box(48, 48, 4, 12, 4)
    val rightLeg = box(0, 16, 4, 12, 4)
    val rightLegOverlay = box(0, 32, 4, 12, 4)
    val leftLeg = box(16, 48, 4, 12, 4)
    val leftLegOverlay = box(0, 48, 4, 12, 4)
}

/** Software painter for textured block-figure models, suitable for both Android and desktop renderers. */
object PlayerSkinRenderer {
    private data class Part(val base: SkinCuboid, val overlay: SkinCuboid, val x: Double, val z: Double,
                            val bottom: Double, val width: Double, val depth: Double, val height: Double)
    private data class Vertex(val x: Double, val y: Double, val depth: Double, val u: Double, val v: Double)

    private val parts = listOf(
        Part(PlayerSkinLayout.rightLeg, PlayerSkinLayout.rightLegOverlay, -0.13, 0.02, 0.0, 0.12, 0.12, 0.76),
        Part(PlayerSkinLayout.leftLeg, PlayerSkinLayout.leftLegOverlay, 0.01, 0.02, 0.0, 0.12, 0.12, 0.76),
        Part(PlayerSkinLayout.torso, PlayerSkinLayout.torsoOverlay, -0.13, 0.02, 0.72, 0.26, 0.13, 0.54),
        Part(PlayerSkinLayout.rightArm, PlayerSkinLayout.rightArmOverlay, -0.27, 0.02, 0.72, 0.12, 0.12, 0.54),
        Part(PlayerSkinLayout.leftArm, PlayerSkinLayout.leftArmOverlay, 0.15, 0.02, 0.72, 0.12, 0.12, 0.54),
        Part(PlayerSkinLayout.head, PlayerSkinLayout.headOverlay, -0.13, 0.02, 1.26, 0.26, 0.26, 0.26)
    )

    /**
     * Draw a player model into an existing raycast frame. [wallDepth] is one distance per screen column
     * (the renderer's wall z-buffer); it prevents the model showing through walls. Coordinates are in
     * world units and the camera uses the game's horizontal yaw/FOV convention.
     */
    fun draw(
        pixels: IntArray, screenWidth: Int, screenHeight: Int, wallDepth: DoubleArray,
        skin: SkinTexture, worldX: Double, worldY: Double, playerYaw: Double,
        cameraX: Double, cameraY: Double, cameraYaw: Double, fovRadians: Double,
        horizon: Int, worldHeight: Double = 1.8, distanceLight: (Double) -> Double = { 1.0 }
    ) {
        require(screenWidth > 0 && screenHeight > 0 && pixels.size == screenWidth * screenHeight)
        require(wallDepth.size == screenWidth && fovRadians in 0.0..Math.PI && fovRadians > 0.0 && worldHeight > 0.0)
        val rightX = -kotlin.math.sin(playerYaw); val rightY = kotlin.math.cos(playerYaw)
        val forwardX = kotlin.math.cos(playerYaw); val forwardY = kotlin.math.sin(playerYaw)
        val cosCamera = kotlin.math.cos(cameraYaw); val sinCamera = kotlin.math.sin(cameraYaw)
        val focal = screenWidth / (2.0 * kotlin.math.tan(fovRadians / 2.0))
        for (part in parts) {
            drawBox(pixels, screenWidth, screenHeight, wallDepth, skin, part, false, worldX, worldY, rightX, rightY,
                forwardX, forwardY, cameraX, cameraY, cosCamera, sinCamera, focal, horizon, worldHeight, distanceLight)
            drawBox(pixels, screenWidth, screenHeight, wallDepth, skin, part, true, worldX, worldY, rightX, rightY,
                forwardX, forwardY, cameraX, cameraY, cosCamera, sinCamera, focal, horizon, worldHeight, distanceLight)
        }
    }

    private fun drawBox(pixels: IntArray, sw: Int, sh: Int, wallDepth: DoubleArray, skin: SkinTexture, part: Part,
                       overlay: Boolean, worldX: Double, worldY: Double, rightX: Double, rightY: Double,
                       forwardX: Double, forwardY: Double, cameraX: Double, cameraY: Double, cosCamera: Double,
                       sinCamera: Double, focal: Double, horizon: Int, worldHeight: Double,
                       light: (Double) -> Double) {
        val expand = if (overlay) 0.012 else 0.0
        val x0 = part.x - expand; val x1 = part.x + part.width + expand
        val z0 = part.z - expand; val z1 = part.z + part.depth + expand
        val y0 = part.bottom - expand; val y1 = part.bottom + part.height + expand
        fun project(px: Double, py: Double, pz: Double): Vertex? {
            val dx = worldX + rightX * px + forwardX * pz - cameraX
            val dy = worldY + rightY * px + forwardY * pz - cameraY
            val depth = dx * cosCamera + dy * sinCamera
            if (depth <= 0.05) return null
            val side = -dx * sinCamera + dy * cosCamera
            return Vertex(sw / 2.0 + side * focal / depth, horizon - py * (sh / worldHeight) * focal / depth, depth, 0.0, 0.0)
        }
        val corners = arrayOf(
            project(x0, y0, z1), project(x1, y0, z1), project(x1, y1, z1), project(x0, y1, z1),
            project(x0, y0, z0), project(x1, y0, z0), project(x1, y1, z0), project(x0, y1, z0)
        )
        val indices = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(5, 0, 3, 6), intArrayOf(4, 5, 6, 7),
            intArrayOf(1, 4, 7, 2), intArrayOf(3, 2, 6, 7), intArrayOf(4, 1, 0, 5)
        )
        val uvFaces = (if (overlay) part.overlay else part.base).faces
        for (faceIndex in indices.indices) {
            val ids = indices[faceIndex]
            val vertices = ids.map { corners[it] ?: return@map null }
            if (vertices.any { it == null }) continue
            val face = uvFaces[faceIndex]
            val uv = arrayOf(face.u to face.v, face.u + face.width to face.v,
                face.u + face.width to face.v + face.height, face.u to face.v + face.height)
            val v = vertices.filterNotNull().mapIndexed { i, vertex -> vertex.copy(u = uv[i].first.toDouble(), v = uv[i].second.toDouble()) }
            triangle(pixels, sw, sh, wallDepth, skin, v[0], v[1], v[2], light)
            triangle(pixels, sw, sh, wallDepth, skin, v[0], v[2], v[3], light)
        }
    }

    private fun triangle(pixels: IntArray, sw: Int, sh: Int, wallDepth: DoubleArray, skin: SkinTexture,
                         a: Vertex, b: Vertex, c: Vertex, light: (Double) -> Double) {
        val minX = max(0, floor(min(a.x, min(b.x, c.x))).toInt())
        val maxX = min(sw - 1, ceil(max(a.x, max(b.x, c.x))).toInt())
        val minY = max(0, floor(min(a.y, min(b.y, c.y))).toInt())
        val maxY = min(sh - 1, ceil(max(a.y, max(b.y, c.y))).toInt())
        if (minX > maxX || minY > maxY) return
        val area = edge(a.x, a.y, b.x, b.y, c.x, c.y)
        if (kotlin.math.abs(area) < 1e-9) return
        val ia = 1.0 / a.depth; val ib = 1.0 / b.depth; val ic = 1.0 / c.depth
        for (py in minY..maxY) for (px in minX..maxX) {
            val w0 = edge(b.x, b.y, c.x, c.y, px + 0.5, py + 0.5) / area
            val w1 = edge(c.x, c.y, a.x, a.y, px + 0.5, py + 0.5) / area
            val w2 = 1.0 - w0 - w1
            if (w0 < -1e-8 || w1 < -1e-8 || w2 < -1e-8) continue
            val invDepth = w0 * ia + w1 * ib + w2 * ic
            if (invDepth <= 0.0) continue
            val depth = 1.0 / invDepth
            if (depth >= wallDepth[px]) continue
            val u = ((w0 * a.u * ia + w1 * b.u * ib + w2 * c.u * ic) / invDepth).toInt().coerceIn(0, 63)
            val v = ((w0 * a.v * ia + w1 * b.v * ib + w2 * c.v * ic) / invDepth).toInt().coerceIn(0, 63)
            val color = skin.sample(u, v)
            val alpha = color ushr 24
            if (alpha == 0) continue
            val factor = light(depth).coerceIn(0.0, 1.5)
            val shaded = (alpha shl 24) or
                (((color shr 16 and 255) * factor).toInt().coerceIn(0, 255) shl 16) or
                (((color shr 8 and 255) * factor).toInt().coerceIn(0, 255) shl 8) or
                ((color and 255) * factor).toInt().coerceIn(0, 255)
            val index = py * sw + px
            if (alpha == 255) pixels[index] = shaded else pixels[index] = blend(pixels[index], shaded, alpha)
        }
    }

    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double) =
        (x - ax) * (by - ay) - (y - ay) * (bx - ax)

    private fun blend(dst: Int, src: Int, alpha: Int): Int {
        val inverse = 255 - alpha
        val r = (((dst shr 16 and 255) * inverse) + ((src shr 16 and 255) * alpha)) / 255
        val g = (((dst shr 8 and 255) * inverse) + ((src shr 8 and 255) * alpha)) / 255
        val b = (((dst and 255) * inverse) + ((src and 255) * alpha)) / 255
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}
