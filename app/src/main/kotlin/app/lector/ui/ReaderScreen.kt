package app.lector.ui

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.lector.ReaderUiState
import app.lector.ReaderViewModel
import app.lector.Screen
import app.lector.core.Sentence
import app.lector.data.LibraryEntry
import app.lector.speech.EngineStatus
import app.lector.speech.VoiceOption
import kotlinx.coroutines.yield

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_OPTIONS = listOf(0, 5, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showVoices by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    // Reader page state, lifted so the reading pane and the bottom controls agree.
    var pages by remember(state.document) { mutableStateOf<List<IntRange>>(emptyList()) }
    var scrub by remember(state.document) { mutableStateOf<Float?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openUri)
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::chooseFolder)
    }

    Scaffold(
        topBar = {
            // Reader has no top bar — its controls live at the bottom (title/Sleep/
            // Voice/Home moved there). Home/Library keep a conventional top bar.
            if (state.screen != Screen.READER) {
                TopAppBar(
                    title = { Text("Lector", maxLines = 1) },
                    actions = {
                        if (state.voices.isNotEmpty()) TextButton(onClick = { showVoices = true }) { Text("Voice") }
                        if (state.screen != Screen.HOME) TextButton(onClick = viewModel::showHome) { Text("Home") }
                    },
                )
            }
        },
        bottomBar = {
            if (state.screen == Screen.READER) {
                ReaderControls(
                    state = state,
                    viewModel = viewModel,
                    pages = pages,
                    scrub = scrub,
                    onScrub = { scrub = it },
                    onScrubCommit = { scrub?.let { v -> viewModel.seekTo(v.toInt()) }; scrub = null },
                    onSleep = { showSleep = true },
                    onVoice = { showVoices = true },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state.screen) {
            Screen.READER -> state.document?.let { doc ->
                PagedReader(
                    document = doc,
                    state = state,
                    scrub = scrub,
                    pages = pages,
                    onPages = { pages = it },
                    onSentenceTap = viewModel::seekTo,
                    modifier = modifier,
                )
            }
            Screen.LIBRARY -> LibraryScreen(state, { viewModel.openUri(Uri.parse(it.uri)) }, { openFolder.launch(null) }, modifier)
            Screen.HOME -> HomeScreen(
                state = state,
                onListen = viewModel::setPastedText,
                onOpenFile = { openFile.launch(arrayOf("*/*")) },
                onOpenFolder = { openFolder.launch(null) },
                onOpenLibrary = viewModel::showLibrary,
                onOpenRecent = { viewModel.openUri(Uri.parse(it.uri)) },
                modifier = modifier,
            )
        }
    }

    if (showVoices) {
        VoiceDialog(state.voices, state.selectedVoiceId, { viewModel.selectVoice(it); showVoices = false }) { showVoices = false }
    }
    if (showSleep) {
        SleepDialog(state.sleepMinutes, { viewModel.setSleepTimer(it); showSleep = false }) { showSleep = false }
    }
    state.importError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            confirmButton = { TextButton(onClick = viewModel::dismissImportError) { Text("OK") } },
            title = { Text("Can’t open file") },
            text = { Text(message) },
        )
    }
}

// ── Paged reader ──────────────────────────────────────────────────────────────

@Composable
private fun PagedReader(
    document: app.lector.core.Document,
    state: ReaderUiState,
    scrub: Float?,
    pages: List<IntRange>,
    onPages: (List<IntRange>) -> Unit,
    onSentenceTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }.toInt()
        // Measurement must match how each sentence actually renders, or pages pack
        // too many and a line spills onto the next page. Each sentence Text has
        // horizontal=8dp, vertical=4dp inner padding inside its Surface.
        val textWidthPx = with(density) { (maxWidth - 16.dp).toPx() }.toInt()
        val itemVPadPx = with(density) { 8.dp.toPx() } // 4dp top + 4dp bottom
        val paraGapPx = with(density) { 20.dp.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }

        // Paginate off the frame: measuring a whole book can take a moment, so we
        // yield periodically to keep the UI responsive (same large-doc lesson as
        // the playback fix).
        LaunchedEffect(document, textWidthPx, heightPx) {
            if (textWidthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            onPages(paginate(document.sentences, textWidthPx, heightPx, style, measurer, itemVPadPx, paraGapPx, gapPx))
        }

        val displaySentence = (scrub?.toInt() ?: state.currentSentence)
            .coerceIn(0, document.sentences.lastIndex)
        val pageIndex = pages.indexOfLast { it.first <= displaySentence }.coerceAtLeast(0)
        val range = pages.getOrNull(pageIndex)

        if (range == null) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text("Preparing pages…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // A page normally fits; verticalScroll is a safety net for a single
            // sentence taller than the screen.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (i in range) {
                    val sentence = document.sentences[i]
                    val isCurrent = sentence.index == state.currentSentence
                    val bg = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
                    Surface(
                        color = bg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSentenceTap(sentence.index) },
                    ) {
                        Text(
                            text = highlighted(sentence, state, isCurrent),
                            style = style,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(if (sentence.endsParagraph) 20.dp else 6.dp))
                }
            }
        }
    }
}

/**
 * Greedily pack sentences into pages that fit [maxHeightPx]; each page is a
 * sentence-index range. [textWidthPx] is the actual text layout width (page width
 * minus the sentence's inner horizontal padding) and [itemVPadPx] the sentence's
 * inner vertical padding, so the measured height matches what renders — otherwise
 * pages overflow and a line spills onto the next page.
 */
private suspend fun paginate(
    sentences: List<Sentence>,
    textWidthPx: Int,
    maxHeightPx: Int,
    style: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
    itemVPadPx: Float,
    paraGapPx: Float,
    gapPx: Float,
): List<IntRange> {
    if (sentences.isEmpty()) return emptyList()
    val pages = mutableListOf<IntRange>()
    var start = 0
    var height = 0f
    var i = 0
    while (i < sentences.size) {
        val textHeight = measurer.measure(
            text = AnnotatedString(sentences[i].text),
            style = style,
            constraints = Constraints(maxWidth = textWidthPx),
        ).size.height.toFloat()
        val gap = if (sentences[i].endsParagraph) paraGapPx else gapPx
        val itemHeight = textHeight + itemVPadPx // full rendered height of this sentence
        if (height > 0f && height + itemHeight > maxHeightPx) {
            pages.add(start until i) // close the page before this sentence
            start = i
            height = 0f
        }
        height += itemHeight + gap
        i++
        if (i % 120 == 0) yield() // keep the UI thread responsive on big books
    }
    if (start < sentences.size) pages.add(start until sentences.size)
    return pages
}

@Composable
private fun ReaderControls(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    pages: List<IntRange>,
    scrub: Float?,
    onScrub: (Float) -> Unit,
    onScrubCommit: () -> Unit,
    onSleep: () -> Unit,
    onVoice: () -> Unit,
) {
    val lastIndex = state.document?.sentences?.lastIndex ?: 0
    val displaySentence = (scrub?.toInt() ?: state.currentSentence).coerceIn(0, maxOf(0, lastIndex))
    val pageIndex = pages.indexOfLast { it.first <= displaySentence }.coerceAtLeast(0)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Moved-from-top navigation: title + Sleep + Voice + Home.
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                Text(
                    state.title ?: "Lector",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSleep) {
                    Text(if (state.sleepMinutes > 0) "Sleep ${state.sleepMinutes}m" else "Sleep")
                }
                if (state.voices.isNotEmpty()) TextButton(onClick = onVoice) { Text("Voice") }
                TextButton(onClick = viewModel::showHome) { Text("Home") }
            }

            // Speed options.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { viewModel.setSpeed(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }

            // Book-length scrubber: drag to move through the whole book.
            if (pages.isNotEmpty()) {
                Text(
                    "Page ${pageIndex + 1} of ${pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = (scrub ?: state.currentSentence.toFloat()).coerceIn(0f, maxOf(1f, lastIndex.toFloat())),
                onValueChange = onScrub,
                onValueChangeFinished = onScrubCommit,
                valueRange = 0f..maxOf(1f, lastIndex.toFloat()),
            )

            // Transport: Back/Next flip pages; Play/Pause toggles playback.
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pages.getOrNull(pageIndex - 1)?.let { viewModel.seekTo(it.first) } },
                    enabled = pageIndex > 0,
                ) { Text("◀ Back") }
                Button(onClick = viewModel::playPause, enabled = state.engineReady) {
                    Text(
                        when (state.engineStatus) {
                            EngineStatus.INITIALIZING -> "Preparing…"
                            EngineStatus.FAILED -> "No engine"
                            EngineStatus.READY -> if (state.isPlaying) "Pause" else "Listen"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { pages.getOrNull(pageIndex + 1)?.let { viewModel.seekTo(it.first) } },
                    enabled = pageIndex < pages.lastIndex,
                ) { Text("Next ▶") }
            }
        }
    }
}

// ── Home / Library ────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
    state: ReaderUiState,
    onListen: (String) -> Unit,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRecent: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        if (state.engineStatus == EngineStatus.FAILED) {
            item { NoEngineNotice(); Spacer(Modifier.height(16.dp)) }
        }
        item {
            Text("What shall I read to you?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenFile) { Text("Open file") }
                OutlinedButton(onClick = onOpenFolder) { Text("Open folder") }
                if (state.library.isNotEmpty()) OutlinedButton(onClick = onOpenLibrary) { Text("Library") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Open a TXT, Markdown, EPUB, PDF, or other text file — a single file or a " +
                    "whole folder (scanned into your library). Or paste text below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (state.recents.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)) }
            items(state.recents, key = { "recent:" + it.uri }) { entry ->
                EntryRow(entry) { onOpenRecent(entry) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = { Text("Paste or type here…") },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { input = it } }) { Text("Paste") }
                Button(onClick = { onListen(input) }, enabled = input.isNotBlank()) { Text("Listen") }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: ReaderUiState,
    onOpen: (LibraryEntry) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Library (${state.library.size})", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onAddFolder) { Text("Add folder") }
        }
        state.scanMessage?.let {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.scanning) CircularProgressIndicator(Modifier.height(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.library.isEmpty() && !state.scanning) {
            Text(
                "Your library is empty. Tap “Add folder” to scan a folder of books; " +
                    "Lector remembers everything it finds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.library, key = { it.uri }) { entry ->
                    EntryRow(entry) { onOpen(entry) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: LibraryEntry, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            val hint = buildString {
                if (entry.format.isNotBlank()) append(entry.format.uppercase())
                if (entry.sentenceIndex > 0) { if (isNotEmpty()) append(" · "); append("resume") }
            }
            if (hint.isNotBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Dialogs & shared bits ─────────────────────────────────────────────────────

@Composable
private fun VoiceDialog(voices: List<VoiceOption>, selectedId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Voice") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item(key = "default-system") {
                    ChoiceRow("Default (system)", selectedId == null) { onSelect(null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                // distinctBy + key: Android Voice.name is not unique (e.g. multiple
                // "zh" voices) — duplicate keys crash Compose ("Key zh was already used").
                // SystemTtsEngine already mints unique ids; this is defense in depth.
                items(voices.distinctBy { it.id }, key = { it.id }) { v ->
                    ChoiceRow(v.label, selectedId == v.id) { onSelect(v.id) }
                }
            }
        },
    )
}

@Composable
private fun SleepDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Sleep timer") },
        text = {
            Column {
                SLEEP_OPTIONS.forEach { m -> ChoiceRow(if (m == 0) "Off" else "$m minutes", current == m) { onPick(m) } }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoEngineNotice() {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("No speech engine found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(
                "Lector needs a text-to-speech engine to read aloud. Install one " +
                    "(for example SherpaTTS or your device’s TTS), then reopen Lector.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Install voice data") }
        }
    }
}

private fun highlighted(sentence: Sentence, state: ReaderUiState, isCurrent: Boolean): AnnotatedString {
    val text = sentence.text
    if (!isCurrent || state.wordStart < 0 || state.wordStart >= text.length) return AnnotatedString(text)
    val end = state.wordEnd.coerceIn(state.wordStart + 1, text.length)
    return buildAnnotatedString {
        append(text.substring(0, state.wordStart))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(state.wordStart, end)) }
        append(text.substring(end))
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
