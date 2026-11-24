package io.github.mrlouix.sonorama.data.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Contenu de `manifest.json` ; le format est décrit dans `docs/format-pack.md`. */
@Serializable
data class PackManifest(
    val format: Int,
    val id: String,
    val version: Int,
    val name: ManifestName,
    val pages: List<ManifestPage> = emptyList(),
    val sounds: List<ManifestSound>,
)

@Serializable
data class ManifestName(val fr: String = "", val en: String = "") {
    fun get(french: Boolean): String = if (french) fr.ifBlank { en } else en.ifBlank { fr }
}

@Serializable
data class ManifestPage(
    val id: String,
    val name: ManifestName,
    val symbol: String = "music_note",
)

@Serializable
data class ManifestSound(
    val id: String,
    val file: String,
    val name: ManifestName,
    val symbol: String = "music_note",
    val defaultVolume: Int = 70,
    val page: String,
    val author: String = "",
    val license: String,
    val source: String = "",
    /** Empreinte SHA-256 du fichier audio, en hexadécimal ; obligatoire dans un pack signé. */
    val sha256: String? = null,
    /** Mots-clés du mode vocal, par code de langue (`fr`, `en`) ; facultatif, ignoré des anciennes versions. */
    val keywords: Map<String, List<String>> = emptyMap(),
)

/** Raison précise d'un refus d'import, affichée dans le bilan. */
enum class PackError {
    NOT_A_ZIP,
    MANIFEST_MISSING,
    MANIFEST_UNREADABLE,
    UNKNOWN_FORMAT,
    INVALID_ID,
    NO_SOUNDS,
    TOO_MANY_SOUNDS,
    DUPLICATE_SOUND,
    INVALID_NAME,
    UNSAFE_PATH,
    UNSUPPORTED_AUDIO,
    UNKNOWN_PAGE,
    FORBIDDEN_LICENSE,
    FILE_MISSING,
    TOO_LARGE,
    RESERVED_ID,
    FIELD_TOO_LONG,
    HASH_MISMATCH,
    SIGNATURE_INVALID,
    SIGNATURE_REQUIRED,
    CHANGED_DURING_IMPORT,
    IO,
}

class PackException(val error: PackError, val detail: String = "") : Exception("$error $detail")

object PackFormat {
    /** Versions de format lisibles ; la 2 ajoute les empreintes et la signature. */
    val VERSIONS = setOf(1, 2)
    const val MANIFEST = "manifest.json"
    const val LICENSES = "LICENSES.md"
    const val SIGNATURE = "manifest.sig"
    const val SIGNING_KEY = "signing-key.pub"
    const val MAX_SIGNATURE_BYTES = 16 * 1024
    const val MAX_SYMBOL = 64
    const val MAX_AUTHOR = 200
    const val MAX_LICENSE = 64
    const val MAX_SOURCE = 500
    const val MAX_KEYWORDS = 30
    const val MAX_KEYWORD = 40
    const val MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_SOUNDS = 500
    const val BASE_PACK_ID = "sonorama.base"

    /** Pages intégrées à l'appli : un pack peut les compléter sans les déclarer. */
    val BUILT_IN_PAGES = setOf("nature", "ville", "technologique", "meteo", "transports")

    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val LANGUAGE = Regex("[a-z]{2}")

    // Liste fermée : CC0, domaine public, CC BY et CC BY-SA, avec version et juridiction éventuelles.
    private val LICENSE = Regex("CC0(-1\\.0)?|PUBLIC-DOMAIN|CC-BY(-SA)?(-[1-4]\\.[0-9](-[A-Z]{2,3})?)?")
    private val RESTRICTED = Regex("(^|-)(NC|ND|NONCOMMERCIAL|NODERIVS|NODERIVATIVES)(-|$)")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PackManifest = try {
        json.decodeFromString(PackManifest.serializer(), text)
    } catch (e: Exception) {
        throw PackException(PackError.MANIFEST_UNREADABLE, e.message.orEmpty().take(200))
    }

    /** Vérifie tout ce qui peut l'être sans ouvrir les fichiers audio. */
    fun validate(manifest: PackManifest) {
        if (manifest.format !in VERSIONS) throw PackException(PackError.UNKNOWN_FORMAT, manifest.format.toString())
        if (!ID.matches(manifest.id)) throw PackException(PackError.INVALID_ID, manifest.id)
        if (manifest.version < 1) throw PackException(PackError.INVALID_ID, "version ${manifest.version}")
        requireName(manifest.name, manifest.id)
        if (manifest.sounds.isEmpty()) throw PackException(PackError.NO_SOUNDS)
        if (manifest.sounds.size > MAX_SOUNDS) throw PackException(PackError.TOO_MANY_SOUNDS)
        val pages = HashSet<String>()
        for (page in manifest.pages) {
            if (!ID.matches(page.id)) throw PackException(PackError.INVALID_ID, page.id)
            requireName(page.name, page.id)
            requireLength(page.symbol, MAX_SYMBOL, page.id)
            pages += page.id
        }
        val ids = HashSet<String>()
        val files = HashSet<String>()
        for (sound in manifest.sounds) {
            if (!ID.matches(sound.id)) throw PackException(PackError.INVALID_ID, sound.id)
            if (!ids.add(sound.id)) throw PackException(PackError.DUPLICATE_SOUND, sound.id)
            requireName(sound.name, sound.id)
            if (!isSafePath(sound.file)) throw PackException(PackError.UNSAFE_PATH, sound.file)
            if (!sound.file.endsWith(".ogg", ignoreCase = true)) {
                throw PackException(PackError.UNSUPPORTED_AUDIO, sound.file)
            }
            if (!files.add(sound.file)) throw PackException(PackError.DUPLICATE_SOUND, sound.file)
            if (sound.page !in pages && sound.page !in BUILT_IN_PAGES) {
                throw PackException(PackError.UNKNOWN_PAGE, sound.page)
            }
            requireLength(sound.license, MAX_LICENSE, sound.id)
            if (!isLicenseAllowed(sound.license)) throw PackException(PackError.FORBIDDEN_LICENSE, sound.license)
            requireLength(sound.symbol, MAX_SYMBOL, sound.id)
            requireLength(sound.author, MAX_AUTHOR, sound.id)
            requireLength(sound.source, MAX_SOURCE, sound.id)
            if (sound.sha256 != null && !SHA256.matches(sound.sha256)) {
                throw PackException(PackError.HASH_MISMATCH, sound.file)
            }
            for ((language, words) in sound.keywords) {
                if (!LANGUAGE.matches(language) || words.size > MAX_KEYWORDS) {
                    throw PackException(PackError.FIELD_TOO_LONG, sound.id)
                }
                words.forEach { requireLength(it, MAX_KEYWORD, sound.id) }
            }
        }
    }

    private fun requireName(name: ManifestName, owner: String) {
        if (name.fr.isBlank() && name.en.isBlank()) throw PackException(PackError.INVALID_NAME, owner)
        if (name.fr.length > 60 || name.en.length > 60) throw PackException(PackError.INVALID_NAME, owner)
    }

    private fun requireLength(value: String, max: Int, owner: String) {
        if (value.length > max) throw PackException(PackError.FIELD_TOO_LONG, owner)
    }

    /** Chemin relatif simple : pas de remontée, pas de racine, pas de lecteur, pas d'antislash. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 200) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        if (path.any { it.code < 0x20 }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** CC0, domaine public, CC BY et CC BY-SA sont acceptées ; rien d'autre, jamais « NC » ni « ND ». */
    fun isLicenseAllowed(license: String): Boolean {
        val id = license.trim().uppercase().replace(' ', '-')
        if (RESTRICTED.containsMatchIn(id)) return false
        return LICENSE.matches(id)
    }
}
