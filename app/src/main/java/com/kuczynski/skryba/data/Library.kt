package com.kuczynski.skryba.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Local library: scanned documents (docs/<id> directory with meta.json and page images)
 * and regular files (PDF, Word, Excel etc.) in the files/ directory. All offline, in app storage.
 */
object Library {
    private lateinit var root: File
    lateinit var appContext: Context
        private set

    val docsDir: File get() = File(root, "docs").apply { mkdirs() }
    val filesDir: File get() = File(root, "files").apply { mkdirs() }
    val signaturesDir: File get() = File(root, "signatures").apply { mkdirs() }
    val sessionDir: File get() = File(root, "session").apply { mkdirs() }
    val tempDir: File get() = File(appContext.cacheDir, "tmp").apply { mkdirs() }

    private val _docs = MutableStateFlow<List<ScanDocument>>(emptyList())
    val docs: StateFlow<List<ScanDocument>> = _docs

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files

    fun init(context: Context) {
        appContext = context.applicationContext
        root = File(context.filesDir, "library").apply { mkdirs() }
        reload()
    }

    fun reload() {
        val list = docsDir.listFiles().orEmpty().mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.exists()) return@mapNotNull null
            runCatching { ScanDocument.fromJson(JSONObject(meta.readText())) }.getOrNull()
        }
        _docs.value = list.sortedByDescending { it.modified }
        reloadFiles()
    }

    fun reloadFiles() {
        _files.value = filesDir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun docDir(id: String): File = File(docsDir, id).apply { mkdirs() }

    fun pageFile(docId: String, name: String): File = File(docDir(docId), name)

    fun get(id: String): ScanDocument? = _docs.value.firstOrNull { it.id == id }

    fun defaultName(prefix: String = str(R.string.doc_prefix_scan)): String =
        prefix + " " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())

    @Synchronized
    fun save(doc: ScanDocument) {
        val d = doc.copy(modified = System.currentTimeMillis())
        val meta = File(docDir(d.id), "meta.json")
        val tmp = File(meta.parentFile, "meta.json.tmp")
        tmp.writeText(d.toJson().toString())
        tmp.renameTo(meta)
        _docs.value = (_docs.value.filter { it.id != d.id } + d).sortedByDescending { it.modified }
        cleanupDocDir(d)
    }

    /** Deletes image files no longer referenced by any page. */
    private fun cleanupDocDir(doc: ScanDocument) {
        val used = HashSet<String>()
        doc.pages.forEach { p ->
            used += p.original
            used += p.processed
        }
        used += "meta.json"
        docDir(doc.id).listFiles().orEmpty().forEach { f ->
            if (f.name !in used && !f.name.endsWith(".tmp")) f.delete()
        }
    }

    fun delete(docId: String) {
        File(docsDir, docId).deleteRecursively()
        _docs.value = _docs.value.filter { it.id != docId }
    }

    fun rename(docId: String, name: String) {
        get(docId)?.let { save(it.copy(name = name.trim().ifEmpty { it.name })) }
    }

    fun duplicate(docId: String): ScanDocument? {
        val src = get(docId) ?: return null
        val id = newId()
        File(docsDir, docId).copyRecursively(File(docsDir, id), overwrite = true)
        val copy = src.copy(id = id, name = str(R.string.copy_suffix, src.name), created = System.currentTimeMillis())
        save(copy)
        return copy
    }

    // ---------- Files ----------

    /** Returns a free file name in the files/ directory, e.g. "Report (2).pdf". */
    fun uniqueFile(name: String): File {
        val clean = sanitize(name)
        var f = File(filesDir, clean)
        if (!f.exists()) return f
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        var i = 2
        while (f.exists()) {
            f = File(filesDir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { str(R.string.file_default) }.take(120)

    fun addFile(src: File, name: String, move: Boolean = false): File {
        val dst = uniqueFile(name)
        if (move) {
            if (!src.renameTo(dst)) {
                src.copyTo(dst)
                src.delete()
            }
        } else {
            src.copyTo(dst)
        }
        reloadFiles()
        return dst
    }

    fun importUri(context: Context, uri: Uri, targetDir: File = filesDir): File {
        val name = displayName(context, uri) ?: "${str(R.string.file_default)}_${System.currentTimeMillis()}"
        val dst = if (targetDir == filesDir) uniqueFile(name) else File(targetDir, sanitize(name))
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        if (targetDir == filesDir) reloadFiles()
        return dst
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun copyToUri(context: Context, file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    fun deleteFile(f: File) {
        f.delete()
        reloadFiles()
    }

    fun renameFile(f: File, newName: String): File {
        var name = sanitize(newName)
        val ext = f.extension
        if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) name += ".$ext"
        val dst = uniqueFile(name)
        f.renameTo(dst)
        reloadFiles()
        return dst
    }

    fun tempFile(name: String): File = File(tempDir, sanitize(name))

    fun clearTemp() {
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ---------- Backup (offline, ZIP file) ----------

    fun exportBackup(out: java.io.OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            listOf(docsDir, filesDir, signaturesDir).forEach { dir ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(root).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Restores a backup. Existing documents with the same id are overwritten, the rest stay. */
    fun importBackup(input: java.io.InputStream): Int {
        var count = 0
        val canonicalRoot = root.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(root, e.name)
                // Protection against "zip slip" (paths escaping the library directory).
                if (!target.canonicalPath.startsWith(canonicalRoot)) continue
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    if (target.name == "meta.json") count++
                }
            }
        }
        reload()
        return count
    }
}
