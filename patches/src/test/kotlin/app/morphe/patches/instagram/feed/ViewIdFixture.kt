/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Whether a vendor Instagram build declares view ids by name, for the patches that change no
 * Instagram code and find their views at runtime by the ids Instagram's layouts give them. A
 * resource table keeps each id's name in a string pool, as UTF-8 or UTF-16.
 */
internal object ViewIdFixture {
    /** The resource table of the build's base APK, read from a bundle or from a plain APK. */
    fun resourceTable(bundle: File): ByteArray {
        if (bundle.extension == "apk") {
            ZipFile(bundle).use { apk ->
                val entry = checkNotNull(apk.getEntry("resources.arsc")) { "${bundle.name} holds no resources.arsc" }
                return apk.getInputStream(entry).use { it.readBytes() }
            }
        }
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (entry.name == "resources.arsc") return apk.readBytes()
                }
            }
        }
        error("${bundle.name} holds no resources.arsc")
    }

    /** The names in [names] the resource table [table] doesn't declare. */
    fun missing(table: ByteArray, names: List<String>): List<String> = names.filterNot { has(table, it) }

    private fun has(table: ByteArray, name: String): Boolean =
        indexOf(table, name.toByteArray(Charsets.UTF_8)) >= 0 || indexOf(table, name.toByteArray(Charsets.UTF_16LE)) >= 0

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
