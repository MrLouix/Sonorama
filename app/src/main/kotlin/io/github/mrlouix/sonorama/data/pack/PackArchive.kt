package io.github.mrlouix.sonorama.data.pack

import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Lecture contrôlée d'une archive de pack. Aucune dépendance à Android : testable sur JVM. */
object PackArchive {

    /** Première passe : trouve, lit et valide le manifeste. */
    fun readManifest(open: () -> InputStream): PackManifest {
        var entries = 0
        val text = try {
            ZipInputStream(open().buffered()).use { zip ->
                var found: String? = null
                while (found == null) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (!entry.isDirectory && entry.name == PackFormat.MANIFEST) {
                        val bytes = zip.readAtMost(PackFormat.MAX_MANIFEST_BYTES + 1)
                        if (bytes.size > PackFormat.MAX_MANIFEST_BYTES) {
                            throw PackException(PackError.MANIFEST_UNREADABLE, "manifest.json > 1 Mo")
                        }
                        found = bytes.decodeToString()
                    }
                }
                found
            }
        } catch (e: ZipException) {
            throw PackException(PackError.NOT_A_ZIP, e.message.orEmpty())
        }
        if (text == null) {
            throw PackException(if (entries == 0) PackError.NOT_A_ZIP else PackError.MANIFEST_MISSING)
        }
        return PackFormat.parse(text).also(PackFormat::validate)
    }

    /**
     * Seconde passe : copie dans [target] les seuls fichiers déclarés par [manifest]
     * (et `LICENSES.md`), en plafonnant la taille décompressée.
     *
     * @param onProgress appelé avec la fraction de fichiers copiés
     * @param checkCancelled appelé régulièrement ; lève une exception pour interrompre
     */
    fun extract(
        manifest: PackManifest,
        open: () -> InputStream,
        target: File,
        maxBytes: Long = PackFormat.MAX_UNCOMPRESSED_BYTES,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ) {
        val wanted = manifest.sounds.map { it.file }.toSet()
        val root = target.canonicalFile
        root.mkdirs()
        val copied = HashSet<String>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(open().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || (name !in wanted && name != PackFormat.LICENSES) || name in copied) continue
                    val out = File(root, name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) {
                        throw PackException(PackError.UNSAFE_PATH, name)
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { stream ->
                        while (true) {
                            checkCancelled()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw PackException(PackError.TOO_LARGE)
                            stream.write(buffer, 0, read)
                        }
                    }
                    copied += name
                    if (name in wanted) onProgress(copied.count { it in wanted } / wanted.size.toFloat())
                }
            }
        } catch (e: ZipException) {
            throw PackException(PackError.NOT_A_ZIP, e.message.orEmpty())
        }
        for (sound in manifest.sounds) {
            val file = File(root, sound.file)
            if (sound.file !in copied) throw PackException(PackError.FILE_MISSING, sound.file)
            if (!isOgg(file)) throw PackException(PackError.UNSUPPORTED_AUDIO, sound.file)
        }
    }

    private fun isOgg(file: File): Boolean = file.inputStream().use { stream ->
        val magic = stream.readAtMost(4)
        magic.size == 4 && magic.decodeToString() == "OggS"
    }
}

/** Lit au plus [limit] octets (équivalent de `readNBytes`, absent avant Android 13). */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
