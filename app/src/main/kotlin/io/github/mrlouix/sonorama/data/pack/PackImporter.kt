package io.github.mrlouix.sonorama.data.pack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.mrlouix.sonorama.data.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ImportStatus { INSTALLED, UPDATED, REPLACED, IGNORED, REFUSED, CANCELLED }

data class ImportResult(
    val fileName: String,
    val packName: ManifestName? = null,
    val status: ImportStatus,
    val soundsAdded: Int = 0,
    val soundCount: Int = 0,
    val error: PackError? = null,
    val detail: String = "",
)

/** Question posée quand le pack importé n'est pas plus récent que celui installé. */
data class ReplaceQuestion(val packName: ManifestName, val installedVersion: Int, val newVersion: Int)

data class ImportState(
    val running: Boolean = false,
    val currentFile: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val progress: Float = 0f,
    val question: ReplaceQuestion? = null,
    /** Bilan à afficher ; vide tant que rien n'a été importé ou après fermeture du bilan. */
    val results: List<ImportResult> = emptyList(),
)

/** Importe des packs ZIP en tâche de fond et tient l'état affiché par l'écran Packs. */
class PackImporter(
    context: Context,
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val packsDir = File(appContext.filesDir, "packs")
    private val stagingDir = File(appContext.cacheDir, "import")

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null

    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || job?.isActive == true) return
        _state.value = ImportState(running = true, fileCount = uris.size)
        job = scope.launch(Dispatchers.IO) {
            val results = ArrayList<ImportResult>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    val name = displayName(uri)
                    _state.update { it.copy(currentFile = name, fileIndex = index, progress = 0f) }
                    results += importOne(uri, name)
                    _state.update { it.copy(results = results.toList()) }
                }
            } finally {
                stagingDir.deleteRecursively()
                _state.update { it.copy(running = false, question = null, results = results.toList()) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        answer?.complete(false)
    }

    /** Réponse à [ImportState.question] : remplacer le pack installé, ou l'ignorer. */
    fun answer(replace: Boolean) {
        answer?.complete(replace)
    }

    fun dismissResults() {
        if (!_state.value.running) _state.value = ImportState()
    }

    /** Libère les fichiers du pack ; ses cartes restent grisées sur les pages personnalisées. */
    suspend fun uninstall(packId: String) = withContext(Dispatchers.IO) {
        repository.uninstallPack(packId)
        File(packsDir, packId).deleteRecursively()
    }

    private suspend fun importOne(uri: Uri, fileName: String): ImportResult {
        val staging = File(stagingDir, UUID.randomUUID().toString())
        var manifest: PackManifest? = null
        try {
            val open = { appContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(fileName) }
            manifest = PackArchive.readManifest(open)
            if (manifest.id == PackFormat.BASE_PACK_ID) throw PackException(PackError.RESERVED_ID, manifest.id)
            val context = coroutineContext
            PackArchive.extract(
                manifest = manifest,
                open = open,
                target = staging,
                onProgress = { fraction -> _state.update { it.copy(progress = fraction) } },
                checkCancelled = { context.ensureActive() },
            )
            val installed = repository.installedPackVersion(manifest.id)
            var status = if (installed == null) ImportStatus.INSTALLED else ImportStatus.UPDATED
            if (installed != null && installed >= manifest.version) {
                val deferred = CompletableDeferred<Boolean>()
                answer = deferred
                _state.update { it.copy(question = ReplaceQuestion(manifest.name, installed, manifest.version)) }
                val replace = deferred.await()
                answer = null
                _state.update { it.copy(question = null) }
                coroutineContext.ensureActive()
                if (!replace) {
                    return ImportResult(fileName, manifest.name, ImportStatus.IGNORED, soundCount = manifest.sounds.size)
                }
                status = ImportStatus.REPLACED
            }
            // À partir d'ici l'installation va jusqu'au bout, même si l'import est annulé.
            val added = withContext(NonCancellable) {
                val target = File(packsDir, manifest.id)
                target.deleteRecursively()
                packsDir.mkdirs()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                }
                repository.installPack(manifest, builtIn = false) { sound ->
                    Uri.fromFile(File(target, sound.file)).toString()
                }
            }
            return ImportResult(fileName, manifest.name, status, added, manifest.sounds.size)
        } catch (e: PackException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, error = e.error, detail = e.detail)
        } catch (e: IOException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, error = PackError.IO, detail = e.message.orEmpty())
        } catch (e: SecurityException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, error = PackError.IO, detail = e.message.orEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.CANCELLED)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "pack.zip"
    }
}
