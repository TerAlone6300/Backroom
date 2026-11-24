package com.backrooms.game

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Built-in fallback skin, available without importing or selecting a user image. */
object DefaultPlayerSkin {
    val texture: SkinTexture by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SkinTexture.fromPng(makePng(makeAtlas()))
    }

    private fun makeAtlas(): IntArray {
        val pixels = IntArray(SkinTexture.SIZE * SkinTexture.SIZE) // transparent outer-layer UV regions
        fun color(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
        val skin = color(211, 165, 126)
        val skinSide = color(183, 132, 98)
        val hair = color(48, 34, 29)
        val hairLight = color(69, 46, 36)
        val shirt = color(47, 105, 117)
        val shirtSide = color(35, 78, 91)
        val shirtLight = color(66, 131, 139)
        val pants = color(48, 53, 67)
        val pantsSide = color(37, 41, 52)
        val boots = color(42, 33, 30)

        fun fill(cuboid: SkinCuboid, colors: IntArray) {
            cuboid.faces.forEachIndexed { index, face ->
                val c = colors[index]
                for (y in face.v until face.v + face.height) for (x in face.u until face.u + face.width)
                    if (x in 0 until 64 && y in 0 until 64) pixels[y * 64 + x] = c
            }
        }
        fill(PlayerSkinLayout.head, intArrayOf(skin, skinSide, skin, skinSide, hair, skin))
        fill(PlayerSkinLayout.torso, intArrayOf(shirt, shirtSide, shirt, shirtSide, shirtLight, shirt))
        fill(PlayerSkinLayout.rightArm, intArrayOf(shirt, shirtSide, shirt, shirtSide, shirtLight, shirt))
        fill(PlayerSkinLayout.leftArm, intArrayOf(shirt, shirtSide, shirt, shirtSide, shirtLight, shirt))
        fill(PlayerSkinLayout.rightLeg, intArrayOf(pants, pantsSide, pants, pantsSide, pants, boots))
        fill(PlayerSkinLayout.leftLeg, intArrayOf(pants, pantsSide, pants, pantsSide, pants, boots))

        // Simple pixel-art face on the head's front UVs (front face is atlas x=8..15, y=8..15).
        for (x in 8..15) pixels[8 * 64 + x] = hair
        for (x in 8..9) pixels[9 * 64 + x] = hairLight
        for (x in 14..15) pixels[9 * 64 + x] = hairLight
        pixels[11 * 64 + 9] = color(42, 56, 76); pixels[11 * 64 + 14] = color(42, 56, 76)
        for (x in 10..13) pixels[14 * 64 + x] = color(142, 80, 68)
        pixels[13 * 64 + 12] = color(190, 129, 103)
        return pixels
    }

    /** Encode the tiny generated atlas as PNG so the same validated decoder handles built-in and user skins. */
    private fun makePng(argb: IntArray): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            for (y in 0 until 64) {
                deflater.write(0) // PNG filter: None
                for (x in 0 until 64) {
                    val c = argb[y * 64 + x]
                    deflater.write(c ushr 16 and 255)
                    deflater.write(c ushr 8 and 255)
                    deflater.write(c and 255)
                    deflater.write(c ushr 24 and 255)
                }
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(64); data.writeInt(64); data.writeByte(8); data.writeByte(6)
                data.writeByte(0); data.writeByte(0); data.writeByte(0)
            }
        }.toByteArray()
        writeChunk(out, "IHDR", header)
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val stream = DataOutputStream(out)
        stream.writeInt(data.size); stream.write(typeBytes); stream.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        stream.writeInt(crc.toInt())
    }
}
