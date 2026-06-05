package com.gguf.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens
// ─────────────────────────────────────────────────────────────────────────────
private val BgDeep      = Color(0xFF080C14)
private val BgCard      = Color(0xFF111827)
private val BgInput     = Color(0xFF0F172A)
private val AccentCyan  = Color(0xFF22D3EE)
private val AccentGreen = Color(0xFF34D399)
private val AccentAmber = Color(0xFFFBBF24)
private val AccentRed   = Color(0xFFF87171)
private val TextPrimary = Color(0xFFF0F4F8)
private val TextSecond  = Color(0xFF94A3B8)
private val ThinkBg     = Color(0xFF0F172A)
private val UserBubble  = Color(0xFF1E3A5F)
private val BotBubble   = Color(0xFF14291A)

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────
enum class Role { USER, ASSISTANT }
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSec: Float = 0f,
    val tokenCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background   = BgDeep,
                    surface      = BgCard,
                    primary      = AccentCyan,
                    onBackground = TextPrimary,
                    onSurface    = TextPrimary
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    GgufEngineScreen()
                }
            }
        }
    }

    fun copyUriToCache(uri: Uri, filename: String, onProgress: (String) -> Unit): String? = try {
        val cacheFile = File(cacheDir, filename)
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            onProgress("Copying model to internal storage…")
            cacheFile.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GgufEngineScreen() {
    val activity       = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val listState      = rememberLazyListState()
    val clipManager    = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ── Model state ──
    var engineStatus  by remember { mutableStateOf("No model loaded") }
    var isLoading     by remember { mutableStateOf(false) }
    var isInferring   by remember { mutableStateOf(false) }
    var modelLoaded   by remember { mutableStateOf(false) }
    var modelFilename by remember { mutableStateOf("") }
    var modelInfo     by remember { mutableStateOf<JSONObject?>(null) }
    var streamedText  by remember { mutableStateOf("") }
    var promptInput   by remember { mutableStateOf("") }
    var kvUsage       by remember { mutableStateOf(0) }
    var tokensPerSec  by remember { mutableStateOf(0f) }
    var inferStartMs  by remember { mutableStateOf(0L) }
    var totalTokens   by remember { mutableStateOf(0) }

    // ── Chat history ──
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }

    // ── Tabs ──
    var selectedTab by remember { mutableStateOf(0) }  // 0=Chat, 1=Settings, 2=Info, 3=Bench

    // ── Settings ──
    var nCtxStr        by remember { mutableStateOf("8192") }
    var maxTokensStr   by remember { mutableStateOf("4096") }
    var tempStr        by remember { mutableStateOf("0.7") }
    var topPStr        by remember { mutableStateOf("0.9") }
    var minPStr        by remember { mutableStateOf("0.05") }
    var gpuLayersStr   by remember { mutableStateOf("99") }
    var repeatPenStr   by remember { mutableStateOf("1.1") }
    var freqPenStr     by remember { mutableStateOf("0.0") }
    var presPenStr     by remember { mutableStateOf("0.0") }
    var systemPrompt   by remember { mutableStateOf("You are a helpful, concise assistant running on-device. Respond clearly and directly.") }

    // ── Benchmark ──
    var benchResult    by remember { mutableStateOf("") }
    var isBenching     by remember { mutableStateOf(false) }

    fun applySettings() {
        val cfg = EngineCore.Config(
            nCtx         = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = maxTokensStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers   = gpuLayersStr.toIntOrNull()?.coerceIn(0, 999) ?: 99,
            seed         = -1
        )
        EngineCore.setEngineConfig(cfg)
        EngineCore.setSystemPromptNative(systemPrompt)
        EngineCore.setRepeatPenalty(EngineCore.RepeatPenaltyConfig(
            repeatPenStr.toFloatOrNull() ?: 1.1f,
            freqPenStr.toFloatOrNull()   ?: 0.0f,
            presPenStr.toFloatOrNull()   ?: 0.0f
        ))
    }

    // ── Stream polling ──
    LaunchedEffect(isInferring) {
        if (isInferring) {
            inferStartMs = System.currentTimeMillis()
            while (isInferring) {
                delay(80)
                val partial = EngineCore.readPartialStream()
                if (partial.isNotEmpty()) streamedText = partial
                val elapsed = (System.currentTimeMillis() - inferStartMs) / 1000f
                val toks    = EngineCore.getTokensGenerated()
                if (elapsed > 0) tokensPerSec = toks / elapsed
                kvUsage = EngineCore.getKvCacheUsageNative()

                if (EngineCore.isInferenceDone()) {
                    val finalText = EngineCore.readTokenStream()
                    val finalToks = EngineCore.getTokensGenerated()
                    val finalTps  = if (elapsed > 0) finalToks / elapsed else 0f
                    totalTokens  += finalToks
                    chatHistory.add(ChatMessage(Role.ASSISTANT, finalText, tokensPerSec = finalTps, tokenCount = finalToks))
                    streamedText = ""
                    isInferring  = false
                }
            }
        }
    }

    LaunchedEffect(chatHistory.size, isInferring) {
        if (chatHistory.isNotEmpty())
            listState.animateScrollToItem(chatHistory.size - 1)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val filename = uri.lastPathSegment
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
                isLoading = true; modelLoaded = false; streamedText = ""
                engineStatus = "Preparing model…"
                coroutineScope.launch(Dispatchers.IO) {
                    val cachedPath = activity.copyUriToCache(uri, filename) { msg ->
                        coroutineScope.launch(Dispatchers.Main) { engineStatus = msg }
                    }
                    if (cachedPath == null) {
                        withContext(Dispatchers.Main) { engineStatus = "Error: Could not read model file."; isLoading = false }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { engineStatus = "Loading into GGML / Vulkan backend…" }
                    applySettings()
                    val success = EngineCore.loadModel(cachedPath)
                    if (success) {
                        try {
                            val raw = EngineCore.getModelInfoNative()
                            modelInfo = JSONObject(raw)
                        } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        isLoading = false; modelLoaded = success; modelFilename = filename
                        engineStatus = if (success) "✓ $filename" else "✗ Load failed (OOM?)"
                        if (success) selectedTab = 0
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // ── Top bar ──
        TopBar(
            engineStatus = engineStatus,
            modelLoaded  = modelLoaded,
            isInferring  = isInferring,
            kvUsage      = kvUsage,
            tokensPerSec = tokensPerSec,
            onLoadClick  = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                }
                filePicker.launch(intent)
            },
            isLoading = isLoading
        )

        // ── Tab bar ──
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = BgCard,
            contentColor     = AccentCyan
        ) {
            listOf("💬 Chat", "⚙ Settings", "ℹ Info", "⚡ Bench").forEachIndexed { i, label ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                    text = { Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
            }
        }

        // ── Tab content ──
        when (selectedTab) {
            0 -> ChatTab(
                chatHistory  = chatHistory,
                listState    = listState,
                streamedText = streamedText,
                isInferring  = isInferring,
                isLoading    = isLoading,
                modelLoaded  = modelLoaded,
                promptInput  = promptInput,
                onPromptChange = { promptInput = it },
                onRun = {
                    if (!isInferring && promptInput.isNotBlank()) {
                        chatHistory.add(ChatMessage(Role.USER, promptInput))
                        val userMsg = promptInput
                        promptInput = ""; streamedText = ""; isInferring = true
                        coroutineScope.launch(Dispatchers.IO) {
                            EngineCore.executeZeroCopyInference(userMsg)
                        }
                    }
                },
                onAbort = {
                    EngineCore.abortInferenceNative()
                },
                onReset = {
                    EngineCore.resetContextNative()
                    chatHistory.clear(); streamedText = ""
                    engineStatus = "Context reset ✓"
                },
                onCopyChat = {
                    val text = EngineCore.exportChatHistoryNative()
                    clipManager.setPrimaryClip(ClipData.newPlainText("Chat", text))
                },
                clipManager = clipManager
            )
            1 -> SettingsTab(
                nCtxStr, { nCtxStr = it },
                maxTokensStr, { maxTokensStr = it },
                tempStr, { tempStr = it },
                topPStr, { topPStr = it },
                minPStr, { minPStr = it },
                gpuLayersStr, { gpuLayersStr = it },
                repeatPenStr, { repeatPenStr = it },
                freqPenStr, { freqPenStr = it },
                presPenStr, { presPenStr = it },
                systemPrompt, { systemPrompt = it },
                onApply = {
                    applySettings()
                    engineStatus = if (modelLoaded) "Settings applied ✓" else "Settings saved (load model first)"
                }
            )
            2 -> InfoTab(modelInfo, modelFilename, totalTokens, modelLoaded)
            3 -> BenchmarkTab(
                modelLoaded  = modelLoaded,
                benchResult  = benchResult,
                isBenching   = isBenching,
                onRunBench   = {
                    if (!isInferring) {
                        isBenching = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val raw = EngineCore.benchmarkNative(512, 128)
                            withContext(Dispatchers.Main) {
                                benchResult = raw; isBenching = false
                            }
                        }
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top Bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TopBar(
    engineStatus: String, modelLoaded: Boolean, isInferring: Boolean,
    kvUsage: Int, tokensPerSec: Float, onLoadClick: () -> Unit, isLoading: Boolean
) {
    Surface(color = BgCard, shadowElevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GGUF ZeroCopy v4",
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = AccentCyan, fontSize = 16.sp)
                    Text(engineStatus,
                        color = if (modelLoaded) AccentGreen else TextSecond,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Button(
                    onClick = onLoadClick,
                    enabled = !isLoading && !isInferring,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (modelLoaded) Color(0xFF1E3A5F) else AccentCyan)
                ) {
                    Text(
                        when { isLoading -> "Loading…"; modelLoaded -> "⟳ Swap Model"; else -> "📂 Load GGUF" },
                        color = if (modelLoaded) AccentCyan else Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
            if (modelLoaded) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChip("KV", "$kvUsage%", if (kvUsage > 80) AccentRed else AccentGreen)
                    if (isInferring)
                        StatChip("TPS", "${"%.1f".format(tokensPerSec)}", AccentAmber)
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0F172A))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat Tab
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ChatTab(
    chatHistory: List<ChatMessage>,
    listState: LazyListState,
    streamedText: String,
    isInferring: Boolean,
    isLoading: Boolean,
    modelLoaded: Boolean,
    promptInput: String,
    onPromptChange: (String) -> Unit,
    onRun: () -> Unit,
    onAbort: () -> Unit,
    onReset: () -> Unit,
    onCopyChat: () -> Unit,
    clipManager: ClipboardManager
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state    = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (chatHistory.isEmpty() && !modelLoaded) {
                item {
                    EmptyState()
                }
            }

            items(chatHistory) { msg ->
                MessageBubble(msg = msg, onCopy = { content ->
                    clipManager.setPrimaryClip(ClipData.newPlainText("Message", content))
                })
            }

            if (isInferring && streamedText.isNotEmpty()) {
                item {
                    StreamingBubble(streamText = streamedText)
                }
            }

            if (isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading model…", color = TextSecond, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }

        // ── Input bar ──
        Surface(color = BgCard, shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (modelLoaded) "Type a message…" else "Load a model first", color = TextSecond, fontSize = 13.sp) },
                    enabled = modelLoaded && !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        disabledTextColor    = TextSecond,
                        disabledBorderColor  = Color(0xFF1E293B)
                    ),
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick  = if (isInferring) onAbort else onRun,
                        enabled  = modelLoaded && !isLoading && (isInferring || promptInput.isNotBlank()),
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (isInferring) AccentRed else AccentCyan)
                    ) {
                        Text(
                            if (isInferring) "■ Stop" else "▶ Send",
                            color      = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onReset,
                        enabled = modelLoaded && !isInferring,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                    ) { Text("↺ Reset", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = onCopyChat,
                        enabled = chatHistory.isNotEmpty(),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = TextSecond)
                    ) { Text("⎘ Copy", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier            = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🤖", fontSize = 48.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("GGUF ZeroCopy v4", fontFamily = FontFamily.Monospace,
            color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("Load a .gguf model to start chatting", color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("Vulkan GPU · Zero-copy IPC · Flash Attention", color = Color(0xFF475569), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentCyan),
                contentAlignment = Alignment.Center
            ) { Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
        }

        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd   = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp, bottomEnd = 16.dp
                    ))
                    .background(if (isUser) UserBubble else BotBubble)
                    .clickable { onCopy(msg.content) }
                    .padding(12.dp)
            ) {
                if (!isUser) {
                    MessageContent(text = msg.content)
                } else {
                    Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            // Metadata
            Row(
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                Text(ts, color = Color(0xFF475569), fontSize = 10.sp)
                if (!isUser && msg.tokensPerSec > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("${"%.1f".format(msg.tokensPerSec)} t/s · ${msg.tokenCount} tok",
                        color = Color(0xFF475569), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentGreen),
                contentAlignment = Alignment.Center
            ) { Text("U", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun MessageContent(text: String) {
    val hasThink    = text.contains("<think>")
    val thinkClosed = text.contains("</think>")
    val thought = if (hasThink) {
        text.substringAfter("<think>").let { if (thinkClosed) it.substringBefore("</think>") else it }
    } else ""
    val response = when {
        hasThink && thinkClosed -> text.substringAfter("</think>").trimStart()
        hasThink                -> ""
        else                    -> text
    }

    Column {
        if (thought.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
                color    = ThinkBg
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠", fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (expanded) "Hide reasoning" else "Show reasoning",
                            fontSize = 11.sp, color = Color(0xFF7C93C3), fontFamily = FontFamily.Monospace)
                        if (!thinkClosed) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(modifier = Modifier.size(9.dp), color = AccentCyan, strokeWidth = 1.dp)
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(thought, fontSize = 12.sp, color = Color(0xFF5A7AAA),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (response.isNotEmpty()) Spacer(Modifier.height(6.dp))
        }
        if (response.isNotEmpty()) {
            Text(response, color = Color(0xFFCCFFC4), fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
fun StreamingBubble(streamText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "cursorAlpha",
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
    )
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentCyan),
            contentAlignment = Alignment.Center) {
            Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(BotBubble)
                .padding(12.dp)
        ) {
            MessageContent(text = streamText)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Tab
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsTab(
    nCtxStr: String,       onNCtxChange: (String) -> Unit,
    maxTokensStr: String,  onMaxTokensChange: (String) -> Unit,
    tempStr: String,       onTempChange: (String) -> Unit,
    topPStr: String,       onTopPChange: (String) -> Unit,
    minPStr: String,       onMinPChange: (String) -> Unit,
    gpuLayersStr: String,  onGpuLayersChange: (String) -> Unit,
    repeatPenStr: String,  onRepeatPenChange: (String) -> Unit,
    freqPenStr: String,    onFreqPenChange: (String) -> Unit,
    presPenStr: String,    onPresPenChange: (String) -> Unit,
    systemPrompt: String,  onSystemPromptChange: (String) -> Unit,
    onApply: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSection("Context & Generation") {
            SettingRow("Context Window (n_ctx)", "512–32768. 8192 is safe for 6–8 GB RAM.",  nCtxStr, onNCtxChange)
            SettingRow("Max New Tokens",          "Tokens per turn (64–8192).",               maxTokensStr, onMaxTokensChange)
            SettingRow("GPU Layers",              "99 = all on Vulkan GPU. 0 = CPU-only.",   gpuLayersStr, onGpuLayersChange)
        }
        SettingsSection("Sampling") {
            SettingRow("Temperature",  "0 = deterministic, 1.0 = creative.",   tempStr, onTempChange)
            SettingRow("Top-P",        "Nucleus sampling (0–1). 0.9 default.", topPStr, onTopPChange)
            SettingRow("Min-P",        "Removes low-prob tokens. 0.05 rec.",   minPStr, onMinPChange)
        }
        SettingsSection("Repetition Penalties") {
            SettingRow("Repeat Penalty",    "Penalise repeated tokens. 1.0 = off.",    repeatPenStr, onRepeatPenChange)
            SettingRow("Frequency Penalty", "Penalise frequent tokens. 0.0 = off.",    freqPenStr, onFreqPenChange)
            SettingRow("Presence Penalty",  "Penalise any seen tokens. 0.0 = off.",    presPenStr, onPresPenChange)
        }
        SettingsSection("System Prompt") {
            OutlinedTextField(
                value = systemPrompt, onValueChange = onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan, unfocusedBorderColor = Color(0xFF334155),
                    focusedTextColor   = TextPrimary, unfocusedTextColor  = Color(0xFFCBD5E1)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )
        }

        // Presets
        Text("Quick Presets", fontSize = 13.sp, color = TextSecond, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Qwen3"     to Triple("8192", "0.6", "You are a helpful assistant."),
                "Gemma 4"   to Triple("8192", "0.7", "You are a helpful assistant."),
                "Reasoning" to Triple("16384","0.6", "Think step-by-step before answering."),
                "Creative"  to Triple("8192", "1.0", "You are a creative storyteller.")
            ).forEach { (label, v) ->
                OutlinedButton(
                    onClick = {
                        onNCtxChange(v.first); onTempChange(v.second)
                        onSystemPromptChange(v.third)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7EA8D8))
                ) { Text(label, fontSize = 11.sp) }
            }
        }

        Button(
            onClick  = onApply,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) { Text("Apply Settings", color = Color.Black, fontWeight = FontWeight.Bold) }

        Text("⚠ n_ctx and GPU layers require a model reload to take effect.",
            fontSize = 11.sp, color = AccentAmber, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = BgCard,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 13.sp, color = AccentCyan,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            content()
        }
    }
}

@Composable
fun SettingRow(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = TextSecond, fontWeight = FontWeight.Medium)
        Text(hint,  fontSize = 10.sp, color = Color(0xFF475569))
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan, unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor   = TextPrimary, unfocusedTextColor  = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Info Tab
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun InfoTab(modelInfo: JSONObject?, modelFilename: String, totalTokens: Int, modelLoaded: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text("Load a model to see info", color = TextSecond)
            }
            return@Column
        }

        InfoCard("Model File", listOf("Name" to modelFilename))

        if (modelInfo != null) {
            val keys = modelInfo.keys()
            val pairs = mutableListOf<Pair<String, String>>()
            while (keys.hasNext()) {
                val k = keys.next()
                pairs.add(k to modelInfo.optString(k, "—"))
            }
            InfoCard("Model Metadata", pairs)
        }

        InfoCard("Session Stats", listOf(
            "Total Tokens Generated" to totalTokens.toString()
        ))

        Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Architecture", fontSize = 13.sp, color = AccentCyan,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("""
ASharedMemory (Ashmem) ring buffer
  write_pos(4) | flags(4) | tokens_gen(4) | reserved(4) | data(512KB)

Kotlin EngineCore reads ByteBuffer mapped read-only.
C++ ipc-bridge writes tokens as UTF-8 into data region.
Kotlin polls isInferenceDoneNative() every 80ms.
                """.trimIndent(),
                    fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp)
            }
        }
    }
}

@Composable
fun InfoCard(title: String, pairs: List<Pair<String, String>>) {
    Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, color = AccentCyan,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            pairs.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(k, fontSize = 12.sp, color = TextSecond,
                        modifier = Modifier.weight(1f))
                    Text(v, fontSize = 12.sp, color = TextPrimary,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Benchmark Tab (FIXED: try-catch outside composable)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BenchmarkTab(
    modelLoaded: Boolean, benchResult: String,
    isBenching: Boolean, onRunBench: () -> Unit
) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("⚡ Performance Benchmark", color = AccentCyan,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Measures prompt-processing (PP) and token-generation (TG) speed.",
            color = TextSecond, fontSize = 12.sp, textAlign = TextAlign.Center)

        Button(
            onClick  = onRunBench,
            enabled  = modelLoaded && !isBenching,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentAmber)
        ) {
            if (isBenching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isBenching) "Benchmarking…" else "▶ Run Benchmark (PP=512, TG=128)",
                color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (!modelLoaded) {
            Text("Load a model first.", color = AccentRed, fontSize = 13.sp)
        }

        // Parse JSON *before* any composable calls
        var ppTps = 0.0
        var tgTps = 0.0
        var parseError = false
        try {
            if (benchResult.isNotEmpty()) {
                val obj = JSONObject(benchResult)
                ppTps = obj.optDouble("pp_tps", 0.0)
                tgTps = obj.optDouble("tg_tps", 0.0)
            }
        } catch (e: Exception) {
            parseError = true
        }

        // Show results using the parsed values (no try-catch inside composition)
        if (benchResult.isNotEmpty() && !parseError && (ppTps > 0 || tgTps > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BenchCard("Prompt Processing", "%.1f".format(ppTps), "tokens/sec")
                BenchCard("Token Generation", "%.1f".format(tgTps), "tokens/sec")
            }
        } else if (benchResult.isNotEmpty()) {
            Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
                Text(benchResult, modifier = Modifier.padding(12.dp),
                    color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun BenchCard(label: String, value: String, unit: String) {
    Surface(
        color  = BgCard,
        shape  = RoundedCornerShape(16.dp),
        modifier = Modifier.width(150.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                color = AccentCyan, fontFamily = FontFamily.Monospace)
            Text(unit,  fontSize = 11.sp, color = TextSecond)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
    }
}
