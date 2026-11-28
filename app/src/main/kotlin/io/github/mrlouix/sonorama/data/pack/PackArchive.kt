package io.github.mrlouix.sonorama.data.pack

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Manifeste lu dans une archive, avec ses octets exacts (ce sont eux qui sont signés). */
class ManifestFile(val manifest: PackManifest, val bytes: ByteArray)

/** Résultat d'une extraction réussie. */
data class ExtractedPack(
    /** Empreinte SHA-256 du fichier ZIP entier. */
    val archiveSha256: String,
    /** Empreinte de la clé qui a signé le pack ; null s'il n'est pas signé. */
    val signerKey: String?,
)

/** Lecture contrôlée d'une archive de pack. Aucune dépendance à Android : testable sur JVM. */
object PackArchive {

    /** Première passe : trouve, lit et valide le manifeste. */
    fun readManifest(open: () -> InputStream): ManifestFile {
        var entries = 0
        val bytes = try {
            ZipInputStream(open().buffered()).use { zip ->
                var found: ByteArray? = null
                while (found == null) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (!entry.isDirectory && entry.name == PackFormat.MANIFEST) {
                        found = zip.readAtMost(PackFormat.MAX_MANIFEST_BYTES + 1)
                        if (found.size > PackFormat.MAX_MANIFEST_BYTES) {
                            throw PackException(PackError.MANIFEST_UNREADABLE, "manifest.json > 1 Mo")
                        }
                    }
                }
                found
            }
        } catch (e: ZipException) {
            throw PackException(PackError.NOT_A_ZIP, e.message.orEmpty())
        }
        if (bytes == null) {
            throw PackException(if (entries == 0) PackError.NOT_A_ZIP else PackError.MANIFEST_MISSING)
        }
        val manifest = PackFormat.parse(bytes.decodeToString()).also(PackFormat::validate)
        return ManifestFile(manifest, bytes)
    }

    /**
     * Seconde passe : copie dans [target] les seuls fichiers déclarés par le manifeste
     * (et `LICENSES.md`), en plafonnant la taille décompressée, puis vérifie la signature
     * et les empreintes des fichiers audio quand le pack en fournit.
     *
     * @param onProgress appelé avec la fraction de fichiers copiés
     * @param checkCancelled appelé régulièrement ; lève une exception pour interrompre
     */
    fun extract(
        file: ManifestFile,
        open: () -> InputStream,
        target: File,
        maxBytes: Long = PackFormat.MAX_UNCOMPRESSED_BYTES,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): ExtractedPack {
        val manifest = file.manifest
        val wanted = manifest.sounds.map { it.file }.toSet()
        val root = target.canonicalFile
        root.mkdirs()
        val copied = HashSet<String>()
        val hashes = HashMap<String, String>()
        var signature: ByteArray? = null
        var signingKey: ByteArray? = null
        var manifestSeen = false
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        try {
            DigestInputStream(open(), archiveDigest).use { raw ->
                val zip = ZipInputStream(raw.buffered())
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    when (name) {
                        PackFormat.MANIFEST -> {
                            // Le manifeste validé à la première passe doit être celui de l'archive copiée.
                            val again = zip.readAtMost(PackFormat.MAX_MANIFEST_BYTES + 1)
                            if (!manifestSeen && !again.contentEquals(file.bytes)) {
                                throw PackException(PackError.CHANGED_DURING_IMPORT)
                            }
                            manifestSeen = true
                            continue
                        }
                        PackFormat.SIGNATURE -> {
                            if (signature == null) signature = zip.readAtMost(PackFormat.MAX_SIGNATURE_BYTES)
                            continue
                        }
                        PackFormat.SIGNING_KEY -> {
                            if (signingKey == null) signingKey = zip.readAtMost(PackFormat.MAX_SIGNATURE_BYTES)
                            continue
                        }
                    }
                    if ((name !in wanted && name != PackFormat.LICENSES) || name in copied) continue
                    val out = File(root, name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) {
                        throw PackException(PackError.UNSAFE_PATH, name)
                    }
                    out.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    out.outputStream().use { stream ->
                        while (true) {
                            checkCancelled()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw PackException(PackError.TOO_LARGE)
                            digest.update(buffer, 0, read)
                            stream.write(buffer, 0, read)
                        }
                    }
                    copied += name
                    hashes[name] = PackSignature.hex(digest.digest())
                    if (name in wanted) onProgress(copied.count { it in wanted } / wanted.size.toFloat())
                }
                // Le lecteur ZIP s'arrête avant la fin du fichier : lire le reste pour l'empreinte.
                while (raw.read(buffer) >= 0) checkCancelled()
            }
        } catch (e: ZipException) {
            throw PackException(PackError.NOT_A_ZIP, e.message.orEmpty())
        }
        if (!manifestSeen) throw PackException(PackError.CHANGED_DURING_IMPORT)
        verifySounds(manifest, root, copied, hashes)
        val signerKey = verifySignature(file, signature, signingKey)
        return ExtractedPack(PackSignature.hex(archiveDigest.digest()), signerKey)
    }

    /** Chaque son déclaré est présent, c'est un Ogg, et son empreinte correspond quand le pack en donne une. */
    private fun verifySounds(manifest: PackManifest, root: File, copied: Set<String>, hashes: Map<String, String>) {
        for (sound in manifest.sounds) {
            if (sound.file !in copied) throw PackException(PackError.FILE_MISSING, sound.file)
            if (!isOgg(File(root, sound.file))) throw PackException(PackError.UNSUPPORTED_AUDIO, sound.file)
            if (sound.sha256 != null && sound.sha256 != hashes[sound.file]) {
                throw PackException(PackError.HASH_MISMATCH, sound.file)
            }
        }
    }

    private fun verifySignature(file: ManifestFile, signature: ByteArray?, signingKey: ByteArray?): String? {
        if (signature == null && signingKey == null) return null
        if (signature == null) throw PackException(PackError.SIGNATURE_INVALID, PackFormat.SIGNATURE)
        if (signingKey == null) throw PackException(PackError.SIGNATURE_INVALID, PackFormat.SIGNING_KEY)
        // Sans empreinte, un fichier audio échapperait à la signature du manifeste.
        file.manifest.sounds.firstOrNull { it.sha256 == null }?.let {
            throw PackException(PackError.SIGNATURE_INVALID, "sha256 absent : ${it.file}")
        }
        return PackSignature.verify(file.bytes, signature.decodeToString(), signingKey.decodeToString())
    }

    private fun isOgg(file: File): Boolean = file.inputStream().use { stream ->
        val magic = stream.readAtMost(4)
        magic.size == 4 && magic.decodeToString() == "OggS"
    }
}

/** Lit au plus [limit] octets (équivalent de `readNBytes`, absent avant Android 13). */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
