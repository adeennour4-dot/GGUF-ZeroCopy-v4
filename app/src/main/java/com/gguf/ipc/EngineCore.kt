package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EngineCore v4 — Kotlin bridge to the C++ llama.cpp JNI engine.
 *
 * New in v4 vs v3:
 *   - abortInferenceNative()     — cancel mid-stream
 *   - getModelInfoNative()       — returns JSON with model metadata
 *   - benchmarkNative()          — PP/TG speed test (tokens/sec)
 *   - setRepeatPenaltyNative()   — repetition / frequency / presence penalty
 *   - exportChatHistoryNative()  — returns full convo as plain text
 *   - getKvCacheUsageNative()    — returns 0-100 fill percentage
 *   - TOKEN_STREAM_SIZE bumped to 512 KB for very long responses
 *   - Extended header: write_pos(4) | flags(4) | tokens_generated(4) | reserved(4)
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy_v4"
    private const val HEADER_SIZE       = 16          // 4 fields × 4 bytes
    private const val TOKEN_STREAM_SIZE = 524288       // 512 KB
    private const val TOTAL_SIZE        = HEADER_SIZE + TOKEN_STREAM_SIZE

    init { System.loadLibrary("ipc-bridge") }

    // -----------------------------------------------------------------------
    // Native declarations
    // -----------------------------------------------------------------------
    private external fun initializeSharedMemoryNative(): Int
    external  fun loadGgufModelNative(filePath: String): Boolean
    external  fun executeZeroCopyInference(prompt: String)
    private external fun getWritePosNative(): Int
    private external fun isInferenceDoneNative(): Boolean
    external  fun abortInferenceNative()

    external fun setEngineConfigNative(
        nCtx: Int, maxNewTokens: Int, temperature: Float,
        topP: Float, minP: Float, nGpuLayers: Int, seed: Int
    )
    external fun setSystemPromptNative(prompt: String)
    external fun resetContextNative()

    /** Returns JSON string: {"arch":"llama","params":7B,...} */
    external fun getModelInfoNative(): String

    /** Runs PP/TG benchmark; returns JSON: {"pp_tps":1200.0,"tg_tps":42.5} */
    external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String

    /** Repetition control parameters */
    external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)

    /** Returns the full conversation history as plain text */
    external fun exportChatHistoryNative(): String

    /** Returns 0-100 KV cache fill % */
    external fun getKvCacheUsageNative(): Int

    // -----------------------------------------------------------------------
    // Shared memory
    // -----------------------------------------------------------------------
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // -----------------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------------
    data class Config(
        val nCtx: Int          = 8192,
        val maxNewTokens: Int  = 4096,
        val temperature: Float = 0.7f,
        val topP: Float        = 0.9f,
        val minP: Float        = 0.05f,
        val nGpuLayers: Int    = 99,
        val seed: Int          = -1
    )

    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float   = 0.0f,
        val presPenalty: Float   = 0.0f
    )

    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(
            cfg.nCtx, cfg.maxNewTokens,
            cfg.temperature, cfg.topP, cfg.minP,
            cfg.nGpuLayers, cfg.seed
        )
    }

    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    // -----------------------------------------------------------------------
    // Boot
    // -----------------------------------------------------------------------
    fun bootZeroCopyEngine() {
        val nativeFd = initializeSharedMemoryNative()
        if (nativeFd < 0) {
            Log.e(TAG, "initializeSharedMemoryNative returned $nativeFd"); return
        }
        try {
            val pfd    = ParcelFileDescriptor.fromFd(nativeFd)
            val dupPfd = pfd.dup()
            sharedMemory = SharedMemory.fromFileDescriptor(dupPfd)
            dupPfd.close()
            readBuffer = sharedMemory!!.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
            Log.i(TAG, "Shared ring buffer mapped. size=$TOTAL_SIZE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map shared memory: ${e.message}", e)
        }
    }

    fun loadModel(path: String): Boolean {
        Log.i(TAG, "Loading model: $path")
        return loadGgufModelNative(path)
    }

    // -----------------------------------------------------------------------
    // Stream reading — extended header (16 bytes)
    // -----------------------------------------------------------------------
    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        buf.position(0)
        val writePos = buf.int.and(0x7FFFFFFF).coerceAtMost(TOKEN_STREAM_SIZE)
        if (writePos == 0) return ""
        buf.position(HEADER_SIZE)
        val bytes = ByteArray(writePos)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun readTokenStream(): String = readPartialStream()

    /** Returns tokens generated count from header field at offset 8 */
    fun getTokensGenerated(): Int {
        val buf = readBuffer ?: return 0
        buf.position(8)
        return buf.int.and(0x7FFFFFFF)
    }

    fun getWritePos(): Int    = getWritePosNative()
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
