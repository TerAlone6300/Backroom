package com.backrooms.game

import java.io.InputStream

/** Platform-neutral loading helpers for player skins. */
object PlayerSkinAssets {
    const val DEFAULT_ASSET = "default_skin.png"

    /**
     * Try a user-provided PNG first, then the packaged default asset, then the generated fallback.
     * [assetReader] is supplied by Android or desktop so this core file has no platform dependencies.
     */
    fun load(userSkinPng: ByteArray? = null, assetReader: (String) -> InputStream? = { null }): SkinTexture {
        userSkinPng?.let { bytes ->
            runCatching { SkinTexture.fromPng(bytes) }.getOrNull()?.let { return it }
        }
        assetReader(DEFAULT_ASSET)?.use { stream ->
            runCatching { SkinTexture.fromPng(stream.readBytes()) }.getOrNull()?.let { return it }
        }
        return DefaultPlayerSkin.texture
    }
}
