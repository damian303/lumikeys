package com.dhobson.lumi.loop

import com.dhobson.lumi.util.TextFolder
import java.io.File

/**
 * Saved loops as small text files in one folder, plus the automatically saved
 * "current loop" that is restored when the app starts. Does disk I/O: call off the
 * main thread.
 */
class LoopStore(folder: File) {
    private val files = TextFolder(folder, EXTENSION)

    /** Names of the saved loops, in alphabetical order. */
    fun list(): List<String> = files.list()

    /** Saves under [name] (already cleaned with [cleanName]), replacing any loop of that name. */
    fun save(name: String, text: String): Boolean = files.save(name, text)

    fun read(name: String): String? = files.read(name)

    fun delete(name: String): Boolean = files.delete(name)

    fun writeAutosave(text: String): Boolean = files.writeFile(AUTOSAVE_NAME, text)

    fun readAutosave(): String? = files.readFile(AUTOSAVE_NAME)

    companion object {
        private const val EXTENSION = ".lumiloop"

        /** Not matched by [list] because it does not end in [EXTENSION]. */
        private const val AUTOSAVE_NAME = "current.autosave"
        const val MAX_NAME_LENGTH = TextFolder.MAX_NAME_LENGTH

        fun cleanName(raw: String): String? = TextFolder.cleanName(raw)
    }
}
