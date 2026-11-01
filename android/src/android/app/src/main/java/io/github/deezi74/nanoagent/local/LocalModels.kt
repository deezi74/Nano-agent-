package io.github.deezi74.nanoagent.local

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Nano Agent: AI models that run on the phone itself. GGUF files are downloaded from Hugging
 * Face (or imported) into the app's models folder and served by [LocalLlm].
 *
 * Downloads resume: the bytes so far live in `<name>.part` and a retry asks for the rest with
 * an HTTP Range request.
 */
object LocalModels {
    private const val TAG = "LocalModels"

    /** A model we recommend, with what it needs. Sizes are the Q4_K_M files on Hugging Face. */
    data class Recommended(
        val id: String,
        val name: String,
        val maker: String,
        val repo: String,
        val file: String,
        val sizeBytes: Long,
        /** Total phone RAM, in GB, below which we warn. */
        val minRamGb: Int,
        val blurb: String,
    ) {
        val url: String get() = hfUrl(repo, file)
    }

    val recommended: List<Recommended> = listOf(
        Recommended(
            "gemma3-1b", "Gemma 3 1B", "Google",
            "bartowski/google_gemma-3-1b-it-GGUF", "google_gemma-3-1b-it-Q4_K_M.gguf",
            806_058_496L, 4, "Fastest. Quick chat on almost any phone.",
        ),
        Recommended(
            "llama3.2-1b", "Llama 3.2 1B", "Meta",
            "bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            807_694_464L, 4, "Fast and follows instructions well.",
        ),
        Recommended(
            "llama3.2-3b", "Llama 3.2 3B", "Meta",
            "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2_019_377_696L, 6, "Smarter; still quick on recent phones.",
        ),
        Recommended(
            "gemma3-4b", "Gemma 3 4B", "Google",
            "bartowski/google_gemma-3-4b-it-GGUF", "google_gemma-3-4b-it-Q4_K_M.gguf",
            2_489_758_112L, 8, "Best all-rounder here. Wants 8 GB of RAM.",
        ),
        Recommended(
            "phi4-mini", "Phi-4 mini", "Microsoft",
            "bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
            2_491_874_688L, 8, "Strong at reasoning and code. Wants 8 GB of RAM.",
        ),
    )

    fun hfUrl(repo: String, file: String): String =
        "https://huggingface.co/$repo/resolve/main/${file.split('/').joinToString("/") { Uri.encode(it) }}?download=true"

    /**
     * "owner/repo:file.gguf", "owner/repo/file.gguf" or a full huggingface.co (or any https)
     * link to a .gguf → (download URL, file name); null when it cannot be read.
     */
    fun parseReference(input: String): Pair<String, String>? {
        val s = input.trim()
        if (s.startsWith("https://", ignoreCase = true)) {
            val path = Uri.parse(s).path ?: return null
            val name = path.substringAfterLast('/')
            if (!name.endsWith(".gguf", ignoreCase = true)) return null
            val url = if (s.contains("huggingface.co") && !s.contains("download=")) {
                s.replace("/blob/", "/resolve/") + (if ('?' in s) "&" else "?") + "download=true"
            } else s
            return url to name
        }
        val (repo, file) = when {
            ':' in s -> s.substringBefore(':') to s.substringAfter(':')
            s.count { it == '/' } >= 2 -> s.split('/').take(2).joinToString("/") to s.split('/').drop(2).joinToString("/")
            else -> return null
        }
        if (repo.count { it == '/' } != 1 || !file.endsWith(".gguf", ignoreCase = true)) return null
        return hfUrl(repo, file) to file.substringAfterLast('/')
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    /** The finished .gguf files, smallest first. */
    fun downloaded(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.length() }.orEmpty()

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / 1_073_741_824.0
    }

    fun freeStorageBytes(context: Context): Long = dir(context).usableSpace

    // ── downloads ──────────────────────────────────────────────────────────────

    data class Progress(
        val name: String,
        val done: Long,
        val total: Long,
        val running: Boolean,
        val error: String? = null,
    ) {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Live downloads by file name (finished ones drop out; failed ones stay with [Progress.error]). */
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    /** Bumped whenever the set of files on disk changes, so screens re-read [downloaded]. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun put(p: Progress) {
        _progress.value = _progress.value + (p.name to p)
    }

    private fun drop(name: String) {
        _progress.value = _progress.value - name
    }

    fun isDownloading(name: String): Boolean = jobs[name]?.isActive == true

    fun download(context: Context, url: String, name: String, expectedBytes: Long = -1L) {
        if (isDownloading(name)) return
        val app = context.applicationContext
        jobs[name] = scope.launch {
            val target = fileFor(app, name)
            val part = File(target.path + ".part")
            try {
                var have = if (part.exists()) part.length() else 0L
                put(Progress(name, have, expectedBytes, running = true))
                val req = Request.Builder().url(url)
                    .header("User-Agent", "NanoAgent-Android")
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 416 && have > 0) {
                        // The part file is already the whole thing.
                    } else {
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        if (resp.code != 206) have = 0L // the server sent it all again
                        val body = resp.body ?: throw IOException("empty response")
                        val total = if (resp.code == 206) {
                            resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: (have + body.contentLength())
                        } else body.contentLength()
                        val needed = (if (total > 0) total else expectedBytes) - have
                        if (needed > 0 && freeStorageBytes(app) < needed + 64L * 1024 * 1024) {
                            throw IOException("Not enough free storage")
                        }
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.setLength(have)
                            raf.seek(have)
                            val buf = ByteArray(256 * 1024)
                            val input = body.byteStream()
                            var last = 0L
                            var done = have
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) {
                                    last = now
                                    put(Progress(name, done, total, running = true))
                                }
                            }
                            if (!isActive) {
                                put(Progress(name, done, total, running = false, error = "Paused"))
                                return@launch
                            }
                            if (total > 0 && done < total) throw IOException("Connection dropped; tap Download to resume")
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Could not save ${target.name}")
                drop(name)
                _revision.value++
                AppLogger.info(TAG, "downloaded $name (${target.length()} bytes)")
            } catch (e: Exception) {
                AppLogger.warning(TAG, "download $name failed: ${e.message}")
                val p = _progress.value[name]
                put(Progress(name, p?.done ?: 0L, p?.total ?: expectedBytes, running = false, error = e.message ?: e.javaClass.simpleName))
            } finally {
                jobs.remove(name)
            }
        }
    }

    fun cancel(name: String) {
        jobs[name]?.cancel()
    }

    /** Remove a model (and any partial download of it). */
    fun delete(context: Context, name: String) {
        cancel(name)
        fileFor(context, name).delete()
        File(fileFor(context, name).path + ".part").delete()
        drop(name)
        _revision.value++
    }

    /** Copy a .gguf the user picked (Files, Downloads, a USB stick) into the models folder. */
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "imported.gguf"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("$name is not a .gguf model file")
        val target = fileFor(context, name.replace('/', '_'))
        val tmp = File(target.path + ".part")
        cr.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, 1024 * 1024) }
        } ?: throw IOException("Could not open $name")
        if (!tmp.renameTo(target)) throw IOException("Could not save $name")
        _revision.value++
        target
    }

    /** "1.9 GB", "806 MB". */
    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
        else -> String.format("%.0f KB", bytes / 1000.0)
    }
}
