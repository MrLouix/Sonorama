package io.github.mrlouix.sonorama.data.pack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.mrlouix.sonorama.data.LibraryRepository
import kotlinx.coroutines.CancellationException
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

enum class ImportStatus { INSTALLED, UPDATED, IGNORED, REFUSED, CANCELLED }

data class ImportResult(
    val fileName: String,
    val packName: ManifestName? = null,
    val status: ImportStatus,
    val soundsAdded: Int = 0,
    val soundCount: Int = 0,
    val trust: PackTrust = PackTrust.UNSIGNED,
    val error: PackError? = null,
    val detail: String = "",
)

/** Confirmation demandée avant de remplacer un pack déjà connu. */
data class ReplaceQuestion(
    val packName: ManifestName,
    /** Version installée ; null si le pack a été désinstallé depuis. */
    val installedVersion: Int?,
    val newVersion: Int,
    val trust: PackTrust,
    val warning: KeyWarning?,
    /** Empreinte SHA-256 du fichier ZIP, affichée à la demande. */
    val archiveSha256: String,
    /** Empreinte de la clé du signataire, affichée à la demande ; null si le pack n'est pas signé. */
    val signerKey: String?,
)

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

/** Importe des packs ZIP en tâche de fond et tient l'état affiché par l'interface. */
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
                    if (!coroutineContext[Job]!!.isActive) {
                        results += ImportResult(name, status = ImportStatus.CANCELLED)
                        continue
                    }
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

    /** Réponse à [ImportState.question] : remplacer le pack connu, ou l'ignorer. */
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
        var trust = PackTrust.UNSIGNED
        try {
            val open = { appContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(fileName) }
            val file = PackArchive.readManifest(open)
            manifest = file.manifest
            if (manifest.id == PackFormat.BASE_PACK_ID) throw PackException(PackError.RESERVED_ID, manifest.id)
            val context = coroutineContext
            val extracted = PackArchive.extract(
                file = file,
                open = open,
                target = staging,
                onProgress = { fraction -> _state.update { it.copy(progress = fraction) } },
                checkCancelled = { context.ensureActive() },
            )
            trust = PackSignature.trustOf(extracted.signerKey)
            val known = repository.knownPack(manifest.id)
            when (val decision = PackSignature.decide(known, extracted.signerKey)) {
                ImportDecision.Install -> Unit
                is ImportDecision.Refuse -> throw PackException(decision.error)
                is ImportDecision.Confirm -> {
                    val question = ReplaceQuestion(
                        packName = manifest.name,
                        installedVersion = known?.takeIf { it.installed }?.version,
                        newVersion = manifest.version,
                        trust = trust,
                        warning = decision.warning,
                        archiveSha256 = extracted.archiveSha256,
                        signerKey = extracted.signerKey,
                    )
                    if (!ask(question)) {
                        return ImportResult(fileName, manifest.name, ImportStatus.IGNORED, soundCount = manifest.sounds.size, trust = trust)
                    }
                }
            }
            // À partir d'ici l'installation va jusqu'au bout, même si l'import est annulé.
            val added = withContext(NonCancellable) {
                val target = File(packsDir, manifest.id)
                swapIn(staging, target)
                repository.installPack(manifest, builtIn = false, extracted.signerKey, extracted.archiveSha256) { sound ->
                    Uri.fromFile(File(target, sound.file)).toString()
                }
            }
            val status = if (known?.installed == true) ImportStatus.UPDATED else ImportStatus.INSTALLED
            return ImportResult(fileName, manifest.name, status, added, manifest.sounds.size, trust)
        } catch (e: PackException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, trust = trust, error = e.error, detail = e.detail)
        } catch (e: IOException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, error = PackError.IO, detail = e.message.orEmpty())
        } catch (e: SecurityException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.REFUSED, error = PackError.IO, detail = e.message.orEmpty())
        } catch (e: CancellationException) {
            return ImportResult(fileName, manifest?.name, ImportStatus.CANCELLED)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun ask(question: ReplaceQuestion): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        _state.update { it.copy(question = question) }
        try {
            val replace = deferred.await()
            coroutineContext.ensureActive()
            return replace
        } finally {
            answer = null
            _state.update { it.copy(question = null) }
        }
    }

    /**
     * Met [staging] à la place de [target]. L'ancien contenu n'est supprimé qu'une fois le nouveau
     * en place ; en cas d'échec il est remis, pour ne jamais laisser un pack installé sans fichiers.
     */
    private fun swapIn(staging: File, target: File) {
        packsDir.mkdirs()
        val backup = File(packsDir, "${target.name}.old")
        backup.deleteRecursively()
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(backup)) throw IOException("${target.name} : remplacement impossible")
        try {
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
            }
        } catch (e: Exception) {
            target.deleteRecursively()
            if (hadOld) backup.renameTo(target)
            throw if (e is IOException) e else IOException(e)
        }
        backup.deleteRecursively()
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
