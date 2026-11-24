package io.github.mrlouix.sonorama.voice

import android.content.Context
import android.net.Uri
import io.github.mrlouix.sonorama.data.pack.PackFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Modèle Vosk officiel accepté par l'appli, reconnu par l'empreinte SHA-256 de son ZIP. */
data class SpeechModel(val language: String, val name: String, val sha256: String, val bundled: Boolean) {
    val fileName: String get() = "$name.zip"
}

object SpeechModels {
    /** Intégré à l'APK ; doit rester identique à la tâche `downloadVoskModel` de `app/build.gradle.kts`. */
    val BUNDLED = SpeechModel(
        language = "fr",
        name = "vosk-model-small-fr-0.22",
        sha256 = "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad",
        bundled = true,
    )

    /** Seuls ces modèles s'importent : un modèle est lu par du code natif, un fichier inconnu est refusé. */
    val KNOWN = listOf(
        BUNDLED,
        SpeechModel(
            language = "en",
            name = "vosk-model-small-en-us-0.15",
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            bundled = false,
        ),
    )

    const val DOWNLOAD_PAGE = "https://alphacephei.com/vosk/models"
    const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 400L * 1024 * 1024

    fun forLanguage(language: String): SpeechModel? = KNOWN.firstOrNull { it.language == language }
}

enum class ModelImportError { UNKNOWN_MODEL, TOO_LARGE, UNREADABLE }

class ModelImportException(val error: ModelImportError) : Exception(error.name)

/** Ce que le mode vocal doit savoir des modèles installés. */
interface SpeechModelSource {
    /** Langues dont le modèle est prêt. */
    val installed: StateFlow<Set<String>>

    /** Dossier du modèle de [language], prêt à ouvrir ; null si aucun modèle n'est installé. */
    suspend fun modelDir(language: String): File?
}

/** Modèles installés dans le dossier privé de l'appli, prêts à être ouverts par Vosk. */
class SpeechModelStore(private val context: Context) : SpeechModelSource {
    private val root = File(context.filesDir, "vosk")
    /** Une seule extraction, import ou suppression à la fois. */
    private val lock = Mutex()

    private val _installed = MutableStateFlow(scanInstalled())
    /** Langues dont le modèle est prêt ; le modèle intégré compte comme prêt, il s'extrait au premier usage. */
    override val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private fun dirOf(model: SpeechModel) = File(root, model.name)

    private fun isReady(model: SpeechModel) = File(dirOf(model), READY_MARKER).isFile

    private fun scanInstalled(): Set<String> =
        SpeechModels.KNOWN.filter { it.bundled || isReady(it) }.map { it.language }.toSet()

    /** Le modèle intégré s'extrait de l'APK au premier usage. */
    override suspend fun modelDir(language: String): File? = lock.withLock { withContext(Dispatchers.IO) {
        val model = SpeechModels.forLanguage(language) ?: return@withContext null
        if (isReady(model)) return@withContext dirOf(model)
        if (!model.bundled) return@withContext null
        context.assets.open("vosk/${model.fileName}").use { extract(model, it) }
        dirOf(model)
    } }

    /** Importe le ZIP officiel d'un modèle ; renvoie le modèle installé. */
    suspend fun import(uri: Uri): SpeechModel = lock.withLock { withContext(Dispatchers.IO) {
        val temp = File.createTempFile("model", ".zip", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: throw ModelImportException(ModelImportError.UNREADABLE)
            input.use { source ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SpeechModels.MAX_ZIP_BYTES) throw ModelImportException(ModelImportError.TOO_LARGE)
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val model = SpeechModels.KNOWN.firstOrNull { it.sha256 == sha }
                ?: throw ModelImportException(ModelImportError.UNKNOWN_MODEL)
            temp.inputStream().use { extract(model, it) }
            _installed.value = scanInstalled()
            model
        } catch (e: ModelImportException) {
            throw e
        } catch (e: Exception) {
            throw ModelImportException(ModelImportError.UNREADABLE)
        } finally {
            temp.delete()
        }
    } }

    /** Supprime un modèle importé ; le modèle intégré se réextraira au besoin. */
    suspend fun delete(language: String) = lock.withLock { withContext(Dispatchers.IO) {
        val model = SpeechModels.forLanguage(language) ?: return@withContext
        dirOf(model).deleteRecursively()
        _installed.value = scanInstalled()
    } }

    /** Extrait les fichiers du dossier racine du modèle, avec les mêmes garde-fous que les packs. */
    private fun extract(model: SpeechModel, input: InputStream) {
        root.mkdirs()
        val staging = File(root, "${model.name}.part")
        staging.deleteRecursively()
        val prefix = "${model.name}/"
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !name.startsWith(prefix)) continue
                val relative = name.removePrefix(prefix)
                if (!PackFormat.isSafePath(relative)) throw ModelImportException(ModelImportError.UNREADABLE)
                val target = File(staging, relative)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SpeechModels.MAX_UNCOMPRESSED_BYTES) throw ModelImportException(ModelImportError.TOO_LARGE)
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!File(staging, "am/final.mdl").isFile) {
            staging.deleteRecursively()
            throw ModelImportException(ModelImportError.UNREADABLE)
        }
        File(staging, READY_MARKER).writeText(model.sha256)
        val dir = dirOf(model)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw ModelImportException(ModelImportError.UNREADABLE)
    }

    private companion object {
        const val READY_MARKER = ".sonorama-ready"
    }
}
