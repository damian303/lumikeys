package com.dhobson.lumi.audio

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * FluidSynth loads SoundFonts from a file path, so the bundled asset is copied to
 * app storage on first run (and again if the bundled file changes size).
 */
object SoundFontInstaller {
    private const val ASSET_NAME = "GeneralUser-GS.sf2"

    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, "soundfonts/$ASSET_NAME")
        // openFd only works because .sf2 assets are stored uncompressed (see app/build.gradle.kts).
        val assetLength = context.assets.openFd(ASSET_NAME).use { it.length }
        if (target.isFile && target.length() == assetLength) {
            return target
        }
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".partial")
        context.assets.open(ASSET_NAME).use { input ->
            partial.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (partial.length() != assetLength) {
            partial.delete()
            throw IOException("SoundFont copy incomplete: ${partial.length()} of $assetLength bytes")
        }
        if (!partial.renameTo(target)) {
            throw IOException("Could not move SoundFont into place at $target")
        }
        return target
    }
}
