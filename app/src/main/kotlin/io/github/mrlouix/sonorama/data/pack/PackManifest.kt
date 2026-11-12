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
    IO,
}

class PackException(val error: PackError, val detail: String = "") : Exception("$error $detail")

object PackFormat {
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val LICENSES = "LICENSES.md"
    const val MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_SOUNDS = 500
    const val BASE_PACK_ID = "sonorama.base"

    /** Pages intégrées à l'appli : un pack peut les compléter sans les déclarer. */
    val BUILT_IN_PAGES = setOf("nature", "ville", "technologique", "meteo", "transports")

    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PackManifest = try {
        json.decodeFromString(PackManifest.serializer(), text)
    } catch (e: Exception) {
        throw PackException(PackError.MANIFEST_UNREADABLE, e.message.orEmpty().take(200))
    }

    /** Vérifie tout ce qui peut l'être sans ouvrir les fichiers audio. */
    fun validate(manifest: PackManifest) {
        if (manifest.format != VERSION) throw PackException(PackError.UNKNOWN_FORMAT, manifest.format.toString())
        if (!ID.matches(manifest.id)) throw PackException(PackError.INVALID_ID, manifest.id)
        if (manifest.version < 1) throw PackException(PackError.INVALID_ID, "version ${manifest.version}")
        requireName(manifest.name, manifest.id)
        if (manifest.sounds.isEmpty()) throw PackException(PackError.NO_SOUNDS)
        if (manifest.sounds.size > MAX_SOUNDS) throw PackException(PackError.TOO_MANY_SOUNDS)
        val pages = HashSet<String>()
        for (page in manifest.pages) {
            if (!ID.matches(page.id)) throw PackException(PackError.INVALID_ID, page.id)
            requireName(page.name, page.id)
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
            if (!isLicenseAllowed(sound.license)) throw PackException(PackError.FORBIDDEN_LICENSE, sound.license)
        }
    }

    private fun requireName(name: ManifestName, owner: String) {
        if (name.fr.isBlank() && name.en.isBlank()) throw PackException(PackError.INVALID_NAME, owner)
        if (name.fr.length > 60 || name.en.length > 60) throw PackException(PackError.INVALID_NAME, owner)
    }

    /** Chemin relatif simple : pas de remontée, pas de racine, pas de lecteur, pas d'antislash. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 200) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        if (path.any { it.code < 0x20 }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** CC0 et CC BY (et CC BY-SA) sont acceptées ; jamais « NC » ni « ND ». */
    fun isLicenseAllowed(license: String): Boolean {
        val id = license.trim().uppercase().replace(' ', '-')
        if (id.isEmpty()) return false
        if (Regex("(^|-)N[CD](-|$)").containsMatchIn(id)) return false
        return id.startsWith("CC0") || id.startsWith("CC-BY") || id == "PUBLIC-DOMAIN"
    }
}
