package io.github.mrlouix.sonorama.data.pack

import android.content.Context
import io.github.mrlouix.sonorama.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pack de base embarqué dans l'APK, au même format que les packs ZIP. */
object BasePack {
    private const val DIR = "basepack"

    /** Enregistre le pack de base au premier lancement ou quand l'appli en apporte une version plus récente. */
    suspend fun ensureInstalled(context: Context, repository: LibraryRepository) = withContext(Dispatchers.IO) {
        val text = context.assets.open("$DIR/${PackFormat.MANIFEST}").use { it.readBytes().decodeToString() }
        val manifest = PackFormat.parse(text).also(PackFormat::validate)
        val installed = repository.installedPackVersion(manifest.id)
        if (installed == null || installed < manifest.version) {
            repository.installPack(manifest, builtIn = true) { sound -> "asset:///$DIR/${sound.file}" }
        }
    }
}
