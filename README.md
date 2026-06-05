# GGUF ZeroCopy Engine — v4

A production-grade Android app for local GGUF model inference.

## What's new in v4 vs v3

| Feature | v3 | v4 |
|---|---|---|
| UI | Single-screen | **Tabbed: Chat / Settings / Info / Bench** |
| Message display | Single response box | **Chat bubbles with timestamps + t/s** |
| Stop inference | ❌ | **✅ Abort button (mid-stream cancel)** |
| Benchmark | ❌ | **✅ PP/TG speed test (tokens/sec)** |
| Model info | ❌ | **✅ arch, params, vocab, context length** |
| Copy chat | ❌ | **✅ Export full conversation** |
| Repeat penalty | ❌ | **✅ repeat / freq / presence penalty** |
| KV cache % | ❌ | **✅ Live fill indicator** |
| Token counter | ❌ | **✅ Per-message t/s + token count** |
| Ring buffer | 256 KB | **512 KB** |
| Header | 8 bytes | **16 bytes (adds tokens_generated field)** |
| Theme | Dark green | **Dark navy — modern chat app look** |

---

## Quick Start — Termux Deploy

The fastest way to build and install from your Android phone:

```bash
# 1. Install Termux from F-Droid (NOT Play Store)
#    https://f-droid.org/packages/com.termux/

# 2. In Termux:
pkg install git
git clone https://github.com/YOUR_USERNAME/GGUF-ZeroCopy-v4
cd GGUF-ZeroCopy-v4

# 3. Run the deploy script — it does everything:
chmod +x termux-deploy.sh
./termux-deploy.sh
```

The script will:
1. Install `git`, `gh` (GitHub CLI), `curl`, `jq`
2. Authenticate you with GitHub (browser or PAT)
3. Create the repo and push the code
4. Trigger the GitHub Actions build
5. Wait (~15-25 min) for the build to finish
6. Download the APK to `~/storage/downloads/`
7. Offer to install it directly

---

## Manual GitHub Actions Build

1. Push to GitHub (any branch)
2. Go to **Actions → Build GGUF ZeroCopy v4 APK → Run workflow**
3. Optionally enter a release tag (e.g. `v4.0.0`) to create a GitHub Release
4. Wait 15–25 min
5. Download `GGUF-ZeroCopy-v4-debug` artifact
6. `adb install GGUF-ZeroCopy-v4-debug-*.apk`

---

## Features

### 💬 Chat Tab
- Full multi-turn conversation with chat bubbles
- User / Assistant roles with colour-coded bubbles
- `<think>` accordion for reasoning models (ZAYA, Qwen3 thinking mode)
- Timestamps and tokens/sec per message
- **Stop button** cancels mid-stream generation
- **Reset** clears history + KV cache
- **Copy** exports the full conversation to clipboard
- Long-press any bubble to copy that message

### ⚙ Settings Tab
- Context window, max tokens, GPU layers
- Temperature, Top-P, Min-P
- **Repeat / Frequency / Presence penalties** (new)
- System prompt with live editing
- Quick presets: Qwen3, Gemma 4, Reasoning, Creative

### ℹ Info Tab
- Model architecture, parameter count, embedding size, vocab size
- Session total token count
- Architecture diagram

### ⚡ Benchmark Tab
- PP (prompt processing) tokens/sec
- TG (token generation) tokens/sec
- Visual result cards

---

## Settings Reference

| Setting | Description | Default |
|---|---|---|
| n_ctx | Context window (512–32768) | 8192 |
| Max New Tokens | Tokens per reply | 4096 |
| GPU Layers | Vulkan GPU layers (0=CPU) | 99 |
| Temperature | 0=deterministic, 1=creative | 0.7 |
| Top-P | Nucleus sampling | 0.9 |
| Min-P | Low-prob token filter | 0.05 |
| Repeat Penalty | Penalise repeated tokens | 1.1 |
| Freq Penalty | Penalise frequent tokens | 0.0 |
| Pres Penalty | Penalise any seen tokens | 0.0 |

---

## Architecture

```
Extended shared ring buffer (512 KB + 16-byte header):
  Offset  0: uint32  write_pos
  Offset  4: uint32  flags (bit0 = done)
  Offset  8: uint32  tokens_generated
  Offset 12: uint32  reserved
  Offset 16: char[]  token_stream (512 KB UTF-8)

C++ ipc-bridge.cpp          Kotlin EngineCore
──────────────────          ──────────────────
ASharedMemory_create()  ──fd──▶ SharedMemory.fromFileDescriptor()
mmap(PROT_READ|WRITE)          .mapReadOnly()
g_abort atomic flag            abortInferenceNative()
benchmarkNative() PP+TG        JSONObject parsing
getModelInfoNative() JSON      InfoTab display
getKvCacheUsageNative()        KV% chip in TopBar
```

---

## Model Compatibility

| Model | Variant | n_ctx |
|---|---|---|
| Qwen3-8B-Instruct | Q4_K_M | 8192 |
| Qwen3.5-7B-Instruct | Q4_K_M | 8192 |
| Gemma-4-9B-IT | Q4_K_M | 8192 |
| Zyphra/ZAYA-1-8B | Q4_K_M | 8192 |
| Phi-4-mini-Instruct | Q4_K_M | 4096 |
| Llama-3.1-8B-Instruct | Q4_K_M | 8192 |

---

## Known Limitations

**Vulkan instability**: Some Adreno driver versions crash. Remove `-DGGML_VULKAN=ON` from `app/build.gradle.kts` to fall back to CPU NEON.

**File copy**: SAF URIs require copying to cacheDir first (~30s for a 4GB model).

**Memory**: Each 1024 tokens context ≈ 200 MB RAM for a Q4_K_M 7B model. Use n_ctx=4096 on low-RAM devices.
