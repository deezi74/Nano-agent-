package io.github.deezi74.nanoagent.local

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Nano Agent: the on-phone model server. llama.cpp's `llama-server` is shipped inside the APK
 * as `libllamaserver.so` (built by scripts/android/build-llama-server.sh) and run as a child
 * process on 127.0.0.1:[PORT]. It speaks the OpenAI API, so to the rest of the app it is an
 * ordinary provider ("On this phone", model id [MODEL_ID]) — chat, streaming and tool calls go
 * through the same code as any cloud provider, and nothing leaves the phone.
 */
object LocalLlm {
    private const val TAG = "LocalLlm"
    const val PORT = 8765
    const val MODEL_ID = "local"
    const val LABEL = "On this phone"
    private const val PREFS = "nanoagent.local"
    private const val KEY_MODEL = "model"
    private const val KEY_CTX = "ctx"
    private const val KEY_AUTO = "auto_start"
    private const val BASE = "http://127.0.0.1:$PORT"

    enum class Status { STOPPED, STARTING, RUNNING, FAILED }

    data class State(
        val status: Status = Status.STOPPED,
        val model: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var process: Process? = null
    private val log = ArrayDeque<String>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    /** False on a build without the engine (x86 test builds). */
    fun engineAvailable(context: Context): Boolean = binary(context).isFile

    fun selectedName(context: Context): String? = prefs(context).getString(KEY_MODEL, null)

    fun selectedFile(context: Context): File? =
        selectedName(context)?.let { LocalModels.fileFor(context, it) }?.takeIf { it.isFile }

    /** Context window in tokens. The agent's instructions and tools are long, so 8K by default. */
    fun contextSize(context: Context): Int = prefs(context).getInt(KEY_CTX, 8192)

    fun setContextSize(context: Context, tokens: Int) {
        prefs(context).edit().putInt(KEY_CTX, tokens).apply()
        if (_state.value.status == Status.RUNNING || _state.value.status == Status.STARTING) start(context)
    }

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoStart(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO, on).apply()

    /** The last lines the server printed, for the screen when something goes wrong. */
    fun recentLog(): String = synchronized(log) { log.joinToString("\n") }

    /**
     * Use [name] (a file in the models folder) for chat: remember it, make sure the provider
     * exists and is the default, and (re)start the server with it.
     */
    fun use(context: Context, name: String) {
        prefs(context).edit().putString(KEY_MODEL, name).apply()
        ensureProvider(context, makeDefault = true)
        start(context)
    }

    /** App start: bring the server up again when a model was chosen. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(2500) // let the app finish starting first
            if (autoStart(app) && selectedFile(app) != null && engineAvailable(app)) {
                ensureProvider(app, makeDefault = false)
                start(app)
            }
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                stopLocked()
                val model = selectedFile(app)
                if (model == null) {
                    _state.value = State(Status.FAILED, null, "Pick a downloaded model first.")
                    return@withLock
                }
                val bin = binary(app)
                if (!bin.isFile) {
                    _state.value = State(Status.FAILED, model.name, "The on-phone engine is not in this build (arm64 phones only).")
                    return@withLock
                }
                _state.value = State(Status.STARTING, model.name, "Loading ${model.name}…")
                synchronized(log) { log.clear() }
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = (cores / 2).coerceIn(2, 6)
                val cmd = listOf(
                    bin.absolutePath,
                    "-m", model.absolutePath,
                    "--host", "127.0.0.1",
                    "--port", PORT.toString(),
                    "-c", contextSize(app).toString(),
                    "-t", threads.toString(),
                    "-np", "1",
                    "--alias", MODEL_ID,
                    "--jinja",
                )
                try {
                    val p = ProcessBuilder(cmd)
                        .directory(app.filesDir)
                        .redirectErrorStream(true)
                        .apply { environment()["HOME"] = app.filesDir.absolutePath }
                        .start()
                    process = p
                    Thread({
                        runCatching {
                            p.inputStream.bufferedReader().forEachLine { line ->
                                synchronized(log) {
                                    log.addLast(line.take(400))
                                    while (log.size > 60) log.removeFirst()
                                }
                            }
                        }
                    }, "llama-server-log").apply { isDaemon = true }.start()
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "start failed: ${e.message}")
                    _state.value = State(Status.FAILED, model.name, "Could not start the engine: ${e.message}")
                    return@withLock
                }
                // Loading a 2 GB model can take a while on a phone.
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    val p = process ?: return@withLock
                    if (!p.isAlive) {
                        val tail = recentLog().lines().takeLast(6).joinToString("\n")
                        _state.value = State(Status.FAILED, model.name, "The engine stopped (exit ${p.exitValue()}).\n$tail")
                        process = null
                        return@withLock
                    }
                    if (healthy()) {
                        _state.value = State(Status.RUNNING, model.name, "Ready")
                        AppLogger.info(TAG, "serving ${model.name} on $BASE")
                        return@withLock
                    }
                    delay(500)
                }
                _state.value = State(Status.FAILED, model.name, "The model took too long to load. Try a smaller one.")
                stopLocked()
            }
        }
    }

    fun stop() {
        scope.launch { lock.withLock { stopLocked(); _state.value = State(Status.STOPPED, _state.value.model, null) } }
    }

    private fun stopLocked() {
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        }
        process = null
    }

    private fun healthy(): Boolean = try {
        val c = URL("$BASE/health").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    /**
     * The provider the chat uses: one OpenAI-compatible instance on 127.0.0.1, one model
     * ([MODEL_ID], whatever file is loaded) and a model group. [makeDefault] also makes that
     * group the one new chats use.
     */
    fun ensureProvider(context: Context, makeDefault: Boolean) {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return
        try {
            val inst = repo.instances.firstOrNull { it.customBaseURL?.trimEnd('/')?.removeSuffix("/v1") == BASE }
                ?: ProviderInstance(
                    id = UUID.randomUUID().toString(),
                    label = LABEL,
                    providerType = ProviderType.openAI,
                    credentialType = ProviderCredential.apiKey,
                    customBaseURL = BASE,
                    appendV1Suffix = true,
                ).also {
                    repo.addInstance(it)
                    repo.saveApiKey(it.id, "on-device")
                }
            val name = selectedName(context)?.removeSuffix(".gguf") ?: "local model"
            repo.replaceEntries(
                inst.id,
                listOf(
                    LLMModel(
                        id = MODEL_ID,
                        displayName = name,
                        provider = "local",
                        contextWindow = contextSize(context),
                        maxOutputTokens = 2048,
                    ),
                ),
            )
            val entry = repo.config.value.modelEntries.firstOrNull { it.providerInstanceId == inst.id && it.model.id == MODEL_ID }
                ?: return
            val groups = repo.config.value.modelGroups
            val named = groups.firstOrNull { it.name == LABEL }
            val group: ModelGroup = groups.firstOrNull { entry.id in it.memberEntryIds }
                ?: named?.copy(memberEntryIds = (named.memberEntryIds + entry.id).toMutableList())?.also { repo.updateGroup(it) }
                ?: ModelGroup(name = LABEL).also { g ->
                    g.memberEntryIds.add(entry.id)
                    repo.addGroup(g)
                }
            val current = repo.config.value.modelGroups.firstOrNull { it.id == repo.defaultPrimaryGroupId }
            if (makeDefault || current == null || current.memberEntryIds.isEmpty()) {
                repo.defaultPrimaryGroupId = group.id
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "provider setup failed: ${e.message}")
        }
    }
}
