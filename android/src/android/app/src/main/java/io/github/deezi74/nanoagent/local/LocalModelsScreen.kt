package io.github.deezi74.nanoagent.local

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseCaption
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseSectionLabel
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch

const val ROUTE_LOCAL_MODELS = "nanoagent_local_models"

private val Ok = Color(0xFF2EBD85)
private val Bad = Color(0xFFE5484D)

/**
 * Settings › On-device models: download a recommended model (or any GGUF from Hugging Face,
 * or import a file), pick the one to use, and start / stop the engine. Picking a model makes
 * "On this phone" the provider new chats use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by LocalLlm.state.collectAsState()
    val progress by LocalModels.progress.collectAsState()
    val revision by LocalModels.revision.collectAsState()
    val files = remember(revision, progress.size) { LocalModels.downloaded(context) }
    val ramGb = remember { LocalModels.totalRamGb(context) }
    var selected by remember { mutableStateOf(LocalLlm.selectedName(context)) }
    var ctx by remember { mutableIntStateOf(LocalLlm.contextSize(context)) }
    var auto by remember { mutableStateOf(LocalLlm.autoStart(context)) }
    var reference by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val engine = remember { LocalLlm.engineAvailable(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { LocalModels.import(context, uri) }
                    .onSuccess { Toast.makeText(context, "Imported ${it.name}", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
                importing = false
            }
        }
    }

    fun use(name: String) {
        selected = name
        LocalLlm.use(context, name)
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("On-device models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            MuseCaption(
                "Run an AI model right on this phone: free, private, works offline. " +
                    "Phones are slower than the cloud, so the first answer can take a minute while the model reads its instructions. " +
                    "This phone has ${String.format("%.0f", ramGb)} GB of RAM.",
            )

            // ── engine status ──
            MuseSectionLabel("Engine")
            MuseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (label, color) = when (server.status) {
                        LocalLlm.Status.RUNNING -> "Running" to Ok
                        LocalLlm.Status.STARTING -> "Starting…" to MuseTones.action
                        LocalLlm.Status.FAILED -> "Stopped with an error" to Bad
                        LocalLlm.Status.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold, color = color)
                        Text(
                            selected?.removeSuffix(".gguf") ?: "No model picked yet",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (server.status == LocalLlm.Status.STARTING) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    when (server.status) {
                        LocalLlm.Status.RUNNING, LocalLlm.Status.STARTING ->
                            OutlinedButton(onClick = { LocalLlm.stop() }) { Text("Stop") }
                        else -> Button(
                            onClick = { LocalLlm.start(context) },
                            enabled = engine && selected != null && LocalLlm.selectedFile(context) != null,
                        ) { Text("Start") }
                    }
                }
                server.message?.takeIf { server.status == LocalLlm.Status.FAILED }?.let { msg ->
                    Text(
                        msg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Bad,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                MuseRowDivider(inset = 16.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Start with the app")
                        Text("Load the picked model whenever Nano Agent opens", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it; LocalLlm.setAutoStart(context, it) })
                }
                MuseRowDivider(inset = 16.dp)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Memory (context window)")
                    Text("Bigger remembers more of the chat but uses more RAM", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4096 to "4K", 8192 to "8K", 16384 to "16K").forEach { (n, l) ->
                            FilterChip(selected = ctx == n, onClick = { ctx = n; LocalLlm.setContextSize(context, n) }, label = { Text(l) })
                        }
                    }
                }
            }
            if (!engine) {
                MuseCaption("The on-phone engine is only included in arm64 builds.")
            }

            // ── downloaded ──
            MuseGap()
            MuseSectionLabel("On this phone")
            MuseCard {
                if (files.isEmpty()) {
                    Text(
                        "No models yet. Download one below or import a .gguf file.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                files.forEachIndexed { i, f ->
                    if (i > 0) MuseRowDivider(inset = 16.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name.removeSuffix(".gguf"), fontWeight = FontWeight.Medium)
                            Text(LocalModels.human(f.length()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected == f.name) {
                            Text("In use", color = Ok, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
                        } else {
                            TextButton(onClick = { use(f.name) }, enabled = engine) { Text("Use") }
                        }
                        TextButton(onClick = {
                            if (selected == f.name) {
                                LocalLlm.stop()
                                selected = null
                            }
                            LocalModels.delete(context, f.name)
                        }) { Text("Delete", color = Bad) }
                    }
                }
                MuseRowDivider(inset = 16.dp)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    if (importing) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) { Text("Import .gguf file") }
                }
            }

            // ── recommended ──
            MuseGap()
            MuseSectionLabel("Recommended")
            MuseCard {
                LocalModels.recommended.forEachIndexed { i, m ->
                    if (i > 0) MuseRowDivider(inset = 16.dp)
                    val have = files.any { it.name == m.file }
                    val p = progress[m.file]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${m.name} · ${m.maker}", fontWeight = FontWeight.Medium)
                                Text(
                                    "${LocalModels.human(m.sizeBytes)} · ${m.blurb}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (ramGb + 0.5 < m.minRamGb) {
                                    Text("May be too big for this phone (${m.minRamGb} GB RAM suggested)", fontSize = 12.sp, color = Bad)
                                }
                            }
                            when {
                                have && selected == m.file -> Text("In use", color = Ok, fontWeight = FontWeight.SemiBold)
                                have -> TextButton(onClick = { use(m.file) }, enabled = engine) { Text("Use") }
                                p?.running == true -> TextButton(onClick = { LocalModels.cancel(m.file) }) { Text("Pause") }
                                else -> Button(onClick = { LocalModels.download(context, m.url, m.file, m.sizeBytes) }) {
                                    Text(if (p != null && p.done > 0) "Resume" else "Download")
                                }
                            }
                        }
                        if (p != null && !have) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                            Text(
                                buildString {
                                    append(LocalModels.human(p.done))
                                    if (p.total > 0) append(" of ").append(LocalModels.human(p.total))
                                    p.error?.let { append(" · ").append(it) }
                                },
                                fontSize = 12.sp,
                                color = if (p.error != null && p.error != "Paused") Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── any model from Hugging Face ──
            MuseGap()
            MuseSectionLabel("Any GGUF from Hugging Face")
            MuseCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = reference,
                        onValueChange = { reference = it },
                        label = { Text("owner/repo:file.gguf or a link") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val parsed = LocalModels.parseReference(reference)
                    val custom = parsed?.let { progress[it.second] }
                    if (custom != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { custom.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            LocalModels.human(custom.done) + (custom.error?.let { " · $it" } ?: ""),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Q4_K_M files of 1B to 4B models run well on most phones.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { parsed?.let { (url, name) -> LocalModels.download(context, url, name) } },
                            enabled = parsed != null && custom?.running != true,
                        ) { Text("Download") }
                    }
                }
            }
            MuseCaption(
                "Picking a model adds the \"On this phone\" provider and makes it the default for new chats. " +
                    "Switch back any time under Settings › Models. Models live in Android/data/<app>/files/models.",
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
