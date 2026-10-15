#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "needle.h"

#ifndef NEEDLEBUB_ENGINE_ABI
#error "NEEDLEBUB_ENGINE_ABI must be defined from vendor/needle/UPSTREAM.json by CMake"
#endif

namespace {
// First four bytes of every Needle 3 .cact archive: little-endian u32 tag 0x05E12A84
// (SDK export TAG). Needle 2 archives carry 0x05E12A83 and must never reach needle_load.
constexpr unsigned char kNeedle3Tag[4] = {0x84, 0x2A, 0xE1, 0x05};
// The fixed header is 196 bytes (48 u32 fields plus one f32); smaller files cannot be archives.
constexpr jlong kMinArchiveBytes = 196;
constexpr jlong kMaxArchiveBytes = 128LL * 1024 * 1024;
// Output buffers start filled with a byte that is not NUL, so only bytes the engine wrote
// can terminate a reply.
constexpr char kUnwrittenFill = static_cast<char>(0xFF);
constexpr int kErrInvalidArgument = -1;
constexpr int kErrMap = -2;
constexpr int kErrArchiveTag = -3;
constexpr int kErrOrder = -4;
constexpr int kErrDescriptor = -5;
constexpr int kErrTelemetry = -6;

std::mutex runtime_mutex;
void* mapped_model = MAP_FAILED;
size_t mapped_size = 0;
bool model_loaded = false;
bool runtime_initialized = false;

// Upstream telemetry is switched off through the environment before any engine call.
// The isolated process has no network permission either; this is the documented switch.
bool disable_telemetry() {
    return setenv("NEEDLE_TELEMETRY", "0", 1) == 0 && setenv("DO_NOT_TRACK", "1", 1) == 0;
}

void release_model() {
    // Reset whenever a model was loaded, even if initialization failed or never ran.
    if (model_loaded) {
        needle_reset();
    }
    runtime_initialized = false;
    model_loaded = false;
    if (mapped_model != MAP_FAILED) {
        munmap(mapped_model, mapped_size);
        mapped_model = MAP_FAILED;
        mapped_size = 0;
    }
}

// Java byte[] carrying real UTF-8, encoded on the Kotlin side. GetStringUTFChars would
// return modified UTF-8, which does not preserve 4-byte sequences such as emoji.
std::string utf8_bytes(JNIEnv* env, jbyteArray value) {
    if (value == nullptr) return {};
    const jsize length = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(&result[0]));
    }
    return result;
}

bool has_embedded_nul(const std::string& value) {
    return value.find('\0') != std::string::npos;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_nativeAbi(JNIEnv* env, jobject) {
    // Build-time pin from UPSTREAM.json, not introspection of the engine.
    // Kotlin compares it with PackManifest.ENGINE_ABI.
    return env->NewStringUTF(NEEDLEBUB_ENGINE_ABI);
}

extern "C" JNIEXPORT jint JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_load(
    JNIEnv*, jobject, jint fd, jlong size) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (!disable_telemetry()) return kErrTelemetry;
    if (fd < 0 || size < kMinArchiveBytes || size > kMaxArchiveBytes) return kErrInvalidArgument;
    // A short or non-regular descriptor would let mmap fault with SIGBUS later, so the
    // declared size must be exactly the size the descriptor reports.
    struct stat info {};
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) ||
        static_cast<jlong>(info.st_size) != size) {
        return kErrDescriptor;
    }
    // Reject foreign archives before the current model is torn down or anything is mapped.
    unsigned char tag[sizeof kNeedle3Tag] = {};
    if (pread(fd, tag, sizeof tag, 0) != static_cast<ssize_t>(sizeof tag) ||
        std::memcmp(tag, kNeedle3Tag, sizeof tag) != 0) {
        return kErrArchiveTag;
    }
    release_model();
    mapped_size = static_cast<size_t>(size);
    mapped_model = mmap(nullptr, mapped_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapped_model == MAP_FAILED) {
        mapped_size = 0;
        return kErrMap;
    }
    const int result = needle_load(static_cast<const unsigned char*>(mapped_model), mapped_size);
    if (result != 0) {
        release_model();
        return result;
    }
    model_loaded = true;
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_initialize(
    JNIEnv* env, jobject, jbyteArray system_prompt, jbyteArray tools_json) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    // Every initialize replaces the previous conversation. Rewind it before reinitializing,
    // and leave the runtime uninitialized unless the new initialization succeeds.
    if (runtime_initialized) {
        needle_reset();
        runtime_initialized = false;
    }
    // Ordering: init is only valid on a model that loaded successfully.
    if (!model_loaded) return kErrOrder;
    if (!disable_telemetry()) return kErrTelemetry;
    const std::string prompt = utf8_bytes(env, system_prompt);
    const std::string tools = utf8_bytes(env, tools_json);
    if (has_embedded_nul(prompt) || has_embedded_nul(tools)) return kErrInvalidArgument;
    const int result = needle_init(prompt.c_str(), tools.c_str(), nullptr);
    runtime_initialized = result >= 0;
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_complete(
    JNIEnv* env, jobject, jbyteArray input, jint max_tokens, jint capacity) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    // Ordering: complete is only valid after a successful initialize.
    if (!runtime_initialized) return nullptr;
    if (!disable_telemetry()) return nullptr;
    const std::string query = utf8_bytes(env, input);
    if (has_embedded_nul(query)) return nullptr;
    const int bounded_capacity = std::clamp(static_cast<int>(capacity), 256, 65536);
    std::vector<char> output(static_cast<size_t>(bounded_capacity), kUnwrittenFill);
    const int result = needle_complete(query.c_str(), max_tokens, output.data(), bounded_capacity);
    if (result < 0) return nullptr;
    // Bounded terminator search over the buffer. Unterminated output is rejected, never
    // repaired by overwriting the final byte.
    const void* terminator = std::memchr(output.data(), '\0', output.size());
    if (terminator == nullptr) return nullptr;
    const size_t length = static_cast<size_t>(static_cast<const char*>(terminator) - output.data());
    jbyteArray reply = env->NewByteArray(static_cast<jsize>(length));
    if (reply == nullptr) return nullptr;
    if (length > 0) {
        env->SetByteArrayRegion(reply, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte*>(output.data()));
    }
    return reply;
}

extern "C" JNIEXPORT void JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_rewind(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    // Rewind conversation state only. The mapped model, the tools and the loaded pack
    // stay resident so the next independent request does not pay for a cold load.
    if (runtime_initialized && disable_telemetry()) {
        needle_reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_reset(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    release_model();
}
