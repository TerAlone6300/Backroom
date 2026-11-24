package com.backrooms.game

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min

/** Decoded 8-bit RGB/RGBA skin image. Pixels use 0xAARRGGBB; transparent pixels are preserved. */
class SkinTexture private constructor(val width: Int, val height: Int, val pixels: IntArray) {
    fun sample(u: Int, v: Int): Int {
        val x = u.coerceIn(0, width - 1)
        val y = v.coerceIn(0, height - 1)
        return pixels[y * width + x]
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

        /**
         * Decode a standard, non-interlaced 8-bit PNG skin without Android or AWT dependencies.
         * Supports true-color RGB, RGBA and indexed images (including palette transparency).
         */
        fun fromPng(bytes: ByteArray): SkinTexture {
            require(bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) { "Not a PNG image" }
            val input = DataInputStream(ByteArrayInputStream(bytes, 8, bytes.size - 8))
            var width = 0; var height = 0; var bitDepth = 0; var colorType = -1; var interlace = 0
            var palette = ByteArray(0); var alpha = ByteArray(0)
            val compressed = java.io.ByteArrayOutputStream()
            while (input.available() >= 12) {
                val length = input.readInt()
                require(length >= 0 && length <= input.available() - 8) { "Invalid PNG chunk length" }
                val typeBytes = ByteArray(4); input.readFully(typeBytes)
                val data = ByteArray(length); input.readFully(data)
                input.readInt() // CRC; structural and decompression validation still catch damaged chunks.
                when (String(typeBytes, Charsets.US_ASCII)) {
                    "IHDR" -> {
                        require(length == 13) { "Invalid PNG header" }
                        val header = DataInputStream(ByteArrayInputStream(data))
                        width = header.readInt(); height = header.readInt(); bitDepth = header.readUnsignedByte()
                        colorType = header.readUnsignedByte()
                        header.readUnsignedByte(); header.readUnsignedByte(); interlace = header.readUnsignedByte()
                    }
                    "PLTE" -> palette = data
                    "tRNS" -> alpha = data
                    "IDAT" -> compressed.write(data)
                    "IEND" -> break
                }
            }
            require(width > 0 && height > 0 && width <= 4096 && height <= 4096) { "Unsupported skin dimensions" }
            require(bitDepth == 8 && interlace == 0) { "Only non-interlaced 8-bit PNG skins are supported" }
            val channels = when (colorType) { 2 -> 3; 3 -> 1; 6 -> 4; else -> error("Unsupported PNG color type: $colorType") }
            require(colorType != 3 || palette.isNotEmpty() && palette.size % 3 == 0) { "Indexed PNG has no valid palette" }
            val stride = Math.multiplyExact(width, channels)
            val raw = ByteArray(Math.multiplyExact(height, stride + 1))
            InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { stream ->
                var offset = 0
                while (offset < raw.size) {
                    val count = stream.read(raw, offset, raw.size - offset)
                    if (count < 0) break
                    offset += count
                }
                require(offset == raw.size && stream.read() == -1) { "Invalid PNG image data" }
            }
            val decoded = ByteArray(height * stride)
            for (y in 0 until height) {
                val src = y * (stride + 1); val dst = y * stride; val filter = raw[src].toInt() and 255
                require(filter in 0..4) { "Invalid PNG row filter" }
                for (x in 0 until stride) {
                    val value = raw[src + 1 + x].toInt() and 255
                    val left = if (x >= channels) decoded[dst + x - channels].toInt() and 255 else 0
                    val up = if (y > 0) decoded[dst + x - stride].toInt() and 255 else 0
                    val upperLeft = if (y > 0 && x >= channels) decoded[dst + x - stride - channels].toInt() and 255 else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        else -> paeth(left, up, upperLeft)
                    }
                    decoded[dst + x] = (value + predictor).toByte()
                }
            }
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val p = i * channels
                val r: Int; val g: Int; val b: Int; val a: Int
                when (colorType) {
                    2 -> { r = decoded[p].toInt() and 255; g = decoded[p + 1].toInt() and 255; b = decoded[p + 2].toInt() and 255; a = 255 }
                    6 -> { r = decoded[p].toInt() and 255; g = decoded[p + 1].toInt() and 255; b = decoded[p + 2].toInt() and 255; a = decoded[p + 3].toInt() and 255 }
                    else -> {
                        val index = (decoded[p].toInt() and 255) * 3
                        require(index + 2 < palette.size) { "PNG palette index out of range" }
                        r = palette[index].toInt() and 255; g = palette[index + 1].toInt() and 255; b = palette[index + 2].toInt() and 255
                        a = alpha.getOrNull(index / 3)?.toInt()?.and(255) ?: 255
                    }
                }
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return SkinTexture(width, height, pixels)
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}

/** A textured face in the classic 64x64 player-skin atlas. */
data class SkinFace(val u: Int, val v: Int, val width: Int, val height: Int)

/** UV regions for the six faces of a cuboid: front, right, back, left, top, bottom. */
data class SkinCuboid(val faces: List<SkinFace>) {
    init { require(faces.size == 6) }
}

/** Standard 64x64 atlas mapping for the head, torso, arms and legs, including outer-layer UVs. */
object PlayerSkinLayout {
    private fun box(x: Int, y: Int, w: Int, h: Int, d: Int) = SkinCuboid(listOf(
        SkinFace(x + d, y + d, w, h),       // front
        SkinFace(x, y + d, d, h),           // right
        SkinFace(x + d + w + d, y + d, w, h), // back
        SkinFace(x + d + w, y + d, d, h),   // left
        SkinFace(x + d, y, w, d),           // top
        SkinFace(x + d + w, y, w, d)        // bottom
    ))

    val head = box(0, 0, 8, 8, 8)
    val headOuter = box(32, 0, 8, 8, 8)
    val torso = box(16, 16, 8, 12, 4)
    val torsoOuter = box(16, 32, 8, 12, 4)
    val rightArm = box(40, 16, 4, 12, 4)
    val rightArmOuter = box(40, 32, 4, 12, 4)
    val leftArm = box(32, 48, 4, 12, 4)
    val leftArmOuter = box(48, 48, 4, 12, 4)
    val rightLeg = box(0, 16, 4, 12, 4)
    val rightLegOuter = box(0, 32, 4, 12, 4)
    val leftLeg = box(16, 48, 4, 12, 4)
    val leftLegOuter = box(0, 48, 4, 12, 4)

    fun validate(texture: SkinTexture) {
        require(texture.width == 64 && texture.height == 64) { "Player skins must be 64×64 PNG images" }
    }
}

/**
 * Camera-space painter for textured cuboids. The game renderer can use this to draw each remote player
 * as a small articulated block model instead of a flat billboard. Coordinates are in world units,
 * and every emitted pixel is depth-tested against the existing raycast z-buffer.
 */
class SkinModelRasterizer(private val width: Int, private val height: Int) {
    private data class Vertex(val x: Double, val y: Double, val z: Double, val u: Double, val v: Double)
    private data class Face(val vertices: List<Vertex>, val depth: Double)
    private val depth = DoubleArray(width * height)

    /** Clear before drawing a frame; walls can be copied into this depth buffer by the caller. */
    fun clearDepth(far: Double = Double.POSITIVE_INFINITY) { depth.fill(far) }
    fun depthBuffer(): DoubleArray = depth

    /** Draw a complete player at ground position (x,y), facing [yaw], using a supplied 64×64 skin. */
    fun drawPlayer(
        pixels: IntArray, skin: SkinTexture, x: Double, y: Double, yaw: Double,
        cameraX: Double, cameraY: Double, cameraYaw: Double, fovRadians: Double,
        horizon: Int, worldHeight: Double = 1.8, distanceFog: (Double) -> Double = { 1.0 }
    ) {
        PlayerSkinLayout.validate(skin)
        require(pixels.size == width * height && fovRadians > 0.0 && fovRadians < Math.PI)
        val rightX = -kotlin.math.sin(yaw); val rightY = kotlin.math.cos(yaw)
        val forwardX = kotlin.math.cos(yaw); val forwardY = kotlin.math.sin(yaw)
        val parts = listOf(
            Part(PlayerSkinLayout.rightLeg, PlayerSkinLayout.rightLegOuter, -0.13, 0.06, 0.34, 0.12, 0.12, 0.76),
            Part(PlayerSkinLayout.leftLeg, PlayerSkinLayout.leftLegOuter, 0.01, 0.06, 0.34, 0.12, 0.12, 0.76),
            Part(PlayerSkinLayout.torso, PlayerSkinLayout.torsoOuter, -0.13, 0.30, 0.32, 0.26, 0.13, 0.52),
            Part(PlayerSkinLayout.rightArm, PlayerSkinLayout.rightArmOuter, -0.28, 0.30, 0.33, 0.12, 0.12, 0.52),
            Part(PlayerSkinLayout.leftArm, PlayerSkinLayout.leftArmOuter, 0.16, 0.30, 0.33, 0.12, 0.12, 0.52),
            Part(PlayerSkinLayout.head, PlayerSkinLayout.headOuter, -0.13, 0.82, 0.30, 0.26, 0.26, 0.26)
        )
        val cosCam = kotlin.math.cos(cameraYaw); val sinCam = kotlin.math.sin(cameraYaw)
        val focal = width / (2.0 * kotlin.math.tan(fovRadians / 2.0))
        val faces = ArrayList<Face>(parts.size * 12)
        for (part in parts) {
            appendBox(faces, part.base, skin, part, x, y, rightX, rightY, forwardX, forwardY,
                cameraX, cameraY, cosCam, sinCam, focal, horizon, worldHeight, false)
            appendBox(faces, part.outer, skin, part, x, y, rightX, rightY, forwardX, forwardY,
                cameraX, cameraY, cosCam, sinCam, focal, horizon, worldHeight, true)
        }
        faces.sortByDescending { it.depth }
        for (face in faces) rasterTriangle(pixels, face.vertices[0], face.vertices[1], face.vertices[2], face.depth, distanceFog)
    }

    private data class Part(val base: SkinCuboid, val outer: SkinCuboid, val ox: Double, val z: Double,
                            val y0: Double, val w: Double, val d: Double, val h: Double)

    private fun appendBox(out: MutableList<Face>, uv: SkinCuboid, skin: SkinTexture, p: Part,
                          x: Double, y: Double, rx: Double, ry: Double, fx: Double, fy: Double,
                          cx: Double, cy: Double, focal: Double, horizon: Int, worldHeight: Double, outer: Boolean) {
        val expand = if (outer) 0.012 else 0.0
        val x0 = p.ox - expand; val x1 = p.ox + p.w + expand
        val z0 = p.z - expand; val z1 = p.z + p.d + expand
        val y0 = p.y0 - expand; val y1 = p.y0 + p.h + expand
        fun vertex(px: Double, py: Double, pz: Double, u: Int, v: Int): Vertex? {
            val wx = x + rx * px + fx * pz
            val wy = y + ry * px + fy * pz
            val dx = wx - cameraX; val dy = wy - cameraY
            val forward = dx * cx + dy * cy
            if (forward <= 0.05) return null
            val side = -dx * cy + dy * cx
            return Vertex(width / 2.0 + side * focal / forward, horizon - py * (height / worldHeight) * focal / forward,
                forward, u.toDouble(), v.toDouble())
        }
        val corners = arrayOf(
            vertex(x0, y0, z1, 0, 0), vertex(x1, y0, z1, 0, 0),
            vertex(x1, y1, z1, 0, 0), vertex(x0, y1, z1, 0, 0),
            vertex(x0, y0, z0, 0, 0), vertex(x1, y0, z0, 0, 0),
            vertex(x1, y1, z0, 0, 0), vertex(x0, y1, z0, 0, 0)
        )
        // Face winding and UV orientation: front, right, back, left, top, bottom.
        val indices = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(5, 0, 3, 6), intArrayOf(4, 5, 6, 7),
            intArrayOf(1, 4, 7, 2), intArrayOf(3, 2, 6, 7), intArrayOf(4, 1, 0, 5))
        val uvFaces = uv.faces
        for (i in indices.indices) {
            val face = uvFaces[i]
            val ids = indices[i]
            val positions = ids.map { corners[it] ?: return@map null }
            if (positions.any { it == null }) continue
            val vtx = positions.filterNotNull().toMutableList()
            val uvs = listOf(
                face.u.toDouble() to face.v.toDouble(),
                (face.u + face.width).toDouble() to face.v.toDouble(),
                (face.u + face.width).toDouble() to (face.v + face.height).toDouble(),
                face.u.toDouble() to (face.v + face.height).toDouble()
            )
            for (j in 0..3) { vtx[j] = vtx[j].copy(u = uvs[j].first, v = uvs[j].second) }
            val avgDepth = vtx.sumOf { it.z } / 4.0
            out.add(Face(listOf(vtx[0], vtx[1], vtx[2]), avgDepth))
            out.add(Face(listOf(vtx[0], vtx[2], vtx[3]), avgDepth))
        }
    }

    private fun rasterTriangle(pixels: IntArray, a: Vertex, b: Vertex, c: Vertex, faceDepth: Double, fog: (Double) -> Double) {
        val minX = max(0, kotlin.math.floor(min(a.x, min(b.x, c.x))).toInt())
        val maxX = min(width - 1, kotlin.math.ceil(max(a.x, max(b.x, c.x))).toInt())
        val minY = max(0, kotlin.math.floor(min(a.y, min(b.y, c.y))).toInt())
        val maxY = min(height - 1, kotlin.math.ceil(max(a.y, max(b.y, c.y))).toInt())
        val area = edge(a.x, a.y, b.x, b.y, c.x, c.y)
        if (area == 0.0 || minX > maxX || minY > maxY) return
        val invA = 1.0 / a.z; val invB = 1.0 / b.z; val invC = 1.0 / c.z
        for (py in minY..maxY) for (px in minX..maxX) {
            val w0 = edge(b.x, b.y, c.x, c.y, px + 0.5, py + 0.5) / area
            val w1 = edge(c.x, c.y, a.x, a.y, px + 0.5, py + 0.5) / area
            val w2 = 1.0 - w0 - w1
            if (w0 < 0 || w1 < 0 || w2 < 0) continue
            val invZ = w0 * invA + w1 * invB + w2 * invC
            val z = 1.0 / invZ
            val index = py * width + px
            if (z >= depth[index]) continue
            val u = ((w0 * a.u * invA + w1 * b.u * invB + w2 * c.u * invC) * z).toInt()
            val v = ((w0 * a.v * invA + w1 * b.v * invB + w2 * c.v * invC) * z).toInt()
            val color = activeSkin?.sample(u, v) ?: continue
            if ((color ushr 24) == 0) continue
            pixels[index] = shade(color, fog(z)); depth[index] = z
        }
    }

    private var activeSkin: SkinTexture? = null
    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double) = (px - ax) * (by - ay) - (py - ay) * (bx - ax)
    private fun shade(color: Int, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.0)
        return (color and -0x1000000) or (((color shr 16 and 255) * f).toInt().coerceIn(0, 255) shl 16) or
            (((color shr 8 and 255) * f).toInt().coerceIn(0, 255) shl 8) or ((color and 255) * f).toInt().coerceIn(0, 255)
    }
}
