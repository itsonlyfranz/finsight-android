package com.example.finsightai.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A model whose file is fully downloaded, verified and registered. */
data class InstalledModel(val spec: ModelSpec, val file: File, val installedAtMs: Long) {
    val id: String get() = spec.id
    val sizeOnDisk: Long get() = file.length()
}

@Serializable
private data class ManifestEntry(val spec: ModelSpec, val installedAtMs: Long)

@Serializable
private data class Manifest(val models: List<ManifestEntry> = emptyList())

/**
 * Owns the on-disk model directory and the "active model" choice.
 *
 * Layout: `<root>/<repo with '/' as '__'>/<file>` for finished files, `<file>.part` while downloading,
 * and `<root>/manifest.json` listing finished models with their metadata (so models found through
 * search keep their name, licence, etc.). A file only enters the manifest after it was verified and
 * atomically renamed, so a crash mid-download can never produce a "ghost" model.
 */
class ModelStore(
    private val root: File,
    private val settings: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val manifestFile get() = File(root, MANIFEST_NAME)

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _active = MutableStateFlow<InstalledModel?>(null)
    val activeModel: StateFlow<InstalledModel?> = _active.asStateFlow()

    init {
        root.mkdirs()
        reload()
    }

    /** Final location for a spec's file. */
    fun targetFile(spec: ModelSpec): File = File(File(root, spec.repoId.replace('/', '_').replace("..", "_") + "__"), safeName(spec.fileName))

    /** Where an in-progress download is kept (enables resume). */
    fun partFile(spec: ModelSpec): File = File(targetFile(spec).path + PART_SUFFIX)

    fun find(id: String): InstalledModel? = _installed.value.firstOrNull { it.id == id }

    fun isInstalled(id: String): Boolean = find(id) != null

    /**
     * Registers a finished download: atomically renames [partFile] into place, then writes the
     * manifest. The first installed model becomes the active one automatically.
     */
    fun commit(spec: ModelSpec, partFile: File): InstalledModel = synchronized(lock) {
        val target = targetFile(spec)
        target.parentFile?.mkdirs()
        atomicReplace(partFile, target)
        val model = InstalledModel(spec, target, clock())
        val updated = _installed.value.filterNot { it.id == spec.id } + model
        try {
            writeManifest(updated)
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        _installed.value = updated
        if (_active.value == null && settings.getString(KEY_ACTIVE).isNullOrBlank()) {
            setActiveLocked(model.id)
        }
        model
    }

    fun setActive(id: String?): Boolean = synchronized(lock) { setActiveLocked(id) }

    private fun setActiveLocked(id: String?): Boolean {
        if (id == null) {
            settings.putString(KEY_ACTIVE, null)
            _active.value = null
            return true
        }
        val model = find(id) ?: return false
        settings.putString(KEY_ACTIVE, id)
        _active.value = model
        return true
    }

    /**
     * Deletes the model file, any `.part` file and the manifest entry. Clears the active choice if it
     * pointed at this model (so the app falls back to deterministic rules / Codex).
     * @return bytes freed on disk (0 when nothing was installed or left over under that id).
     */
    fun delete(id: String): Long = synchronized(lock) {
        val model = find(id)
        val spec = model?.spec ?: CuratedModels.byId(id)
        var freed = 0L
        model?.file?.let { f -> if (f.isFile) { freed += f.length(); f.delete() } }
        spec?.let { s -> partFile(s).let { p -> if (p.isFile) { freed += p.length(); p.delete() } } }
        if (model != null) {
            val updated = _installed.value.filterNot { it.id == id }
            _installed.value = updated
            if (_active.value?.id == id) {
                settings.putString(KEY_ACTIVE, null)
                _active.value = null
            }
            try {
                writeManifest(updated)
            } catch (_: IOException) {
            }
        }
        freed
    }

    /** Removes an unfinished download's partial file (UI cancel button). Returns bytes freed. */
    fun discardPartial(spec: ModelSpec): Long {
        val p = partFile(spec)
        val n = if (p.isFile) p.length() else 0L
        p.delete()
        return n
    }

    private fun reload() = synchronized(lock) {
        val entries = try {
            if (manifestFile.isFile) json.decodeFromString<Manifest>(manifestFile.readText()).models else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // Drop entries whose file vanished or no longer has the expected size.
        val valid = entries.mapNotNull { e ->
            val f = targetFile(e.spec)
            if (f.isFile && f.length() == e.spec.sizeBytes) InstalledModel(e.spec, f, e.installedAtMs) else null
        }
        if (valid.size != entries.size) writeManifest(valid)
        _installed.value = valid
        val activeId = settings.getString(KEY_ACTIVE)
        _active.value = valid.firstOrNull { it.id == activeId }
        if (_active.value == null && activeId != null) settings.putString(KEY_ACTIVE, null)
    }

    private fun writeManifest(models: List<InstalledModel>) {
        root.mkdirs()
        val text = json.encodeToString(Manifest.serializer(), Manifest(models.map { ManifestEntry(it.spec, it.installedAtMs) }))
        val tmp = File(root, "$MANIFEST_NAME.tmp")
        try {
            tmp.writeText(text)
            atomicReplace(tmp, manifestFile)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    /** POSIX rename(2): atomic and replaces [to] on the same filesystem; works on every API level. */
    private fun atomicReplace(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Could not move ${from.name} to ${to.name}")
    }

    private fun safeName(name: String) = name.substringAfterLast('/').replace("..", "_")

    companion object {
        const val PART_SUFFIX = ".part"
        const val MANIFEST_NAME = "manifest.json"
        const val KEY_ACTIVE = "active_model_id"
    }
}
