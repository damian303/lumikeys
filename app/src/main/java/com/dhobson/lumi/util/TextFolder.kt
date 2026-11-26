package com.dhobson.lumi.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Small named text files of one kind in one folder: saved loops, imported songs.
 * Does disk I/O: call off the main thread.
 */
class TextFolder(private val folder: File, private val extension: String) {
    /** Names of the saved items, in alphabetical order. */
    fun list(): List<String> =
        folder.listFiles { file -> file.isFile && file.name.endsWith(extension) }
            .orEmpty()
            .map { it.name.removeSuffix(extension) }
            .sortedBy { it.lowercase() }

    /** Saves under [name] (already cleaned with [cleanName]), replacing any item of that name. */
    fun save(name: String, text: String): Boolean = writeFile(name + extension, text)

    fun read(name: String): String? = readFile(name + extension)

    fun delete(name: String): Boolean = File(folder, name + extension).delete()

    /**
     * Writes to a temporary file first, so a crash part-way cannot leave half a file,
     * and forces it onto the disk before giving it its real name. Without that, losing
     * power a moment after saving left an empty file under the real name (seen on an
     * emulator that was shut down abruptly a second after a save).
     */
    fun writeFile(fileName: String, text: String): Boolean = try {
        folder.mkdirs()
        val target = File(folder, fileName)
        val partial = File(folder, "$fileName.partial")
        FileOutputStream(partial).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) throw IOException("cannot replace $fileName")
        if (!partial.renameTo(target)) throw IOException("cannot move ${partial.name} into place")
        true
    } catch (e: IOException) {
        false
    }

    fun readFile(fileName: String): String? = try {
        File(folder, fileName).takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        null
    }

    companion object {
        const val MAX_NAME_LENGTH = 30

        /**
         * A name that is safe as a file name: letters, digits, spaces, hyphens and
         * underscores only. Null if nothing usable is left.
         */
        fun cleanName(raw: String): String? {
            val cleaned = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .trim()
                .replace(Regex(" +"), " ")
                .take(MAX_NAME_LENGTH)
                .trim()
            return cleaned.ifEmpty { null }
        }
    }
}
