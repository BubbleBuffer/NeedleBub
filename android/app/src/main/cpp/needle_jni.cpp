#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "needle.h"

namespace {
std::mutex runtime_mutex;
void* mapped_model = MAP_FAILED;
size_t mapped_size = 0;
bool runtime_initialized = false;

void release_model() {
    if (runtime_initialized) {
        needle_reset();
        runtime_initialized = false;
    }
    if (mapped_model != MAP_FAILED) {
        munmap(mapped_model, mapped_size);
        mapped_model = MAP_FAILED;
        mapped_size = 0;
    }
}

std::string utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_load(
    JNIEnv*, jobject, jint fd, jlong size) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (fd < 0 || size <= 0) return -1;
    release_model();
    mapped_size = static_cast<size_t>(size);
    mapped_model = mmap(nullptr, mapped_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (mapped_model == MAP_FAILED) {
        mapped_size = 0;
        return -2;
    }
    const int result = needle_load(static_cast<const unsigned char*>(mapped_model), mapped_size);
    if (result != 0) release_model();
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_initialize(
    JNIEnv* env, jobject, jstring system_prompt, jstring tools_json) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    const std::string prompt = utf8(env, system_prompt);
    const std::string tools = utf8(env, tools_json);
    const int result = needle_init(prompt.c_str(), tools.c_str(), nullptr);
    runtime_initialized = result >= 0;
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_complete(
    JNIEnv* env, jobject, jstring input, jint max_tokens, jint capacity) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    const std::string query = utf8(env, input);
    const int bounded_capacity = std::clamp(static_cast<int>(capacity), 256, 65536);
    std::vector<char> output(static_cast<size_t>(bounded_capacity), '\0');
    const int result = needle_complete(query.c_str(), max_tokens, output.data(), bounded_capacity);
    if (result < 0) return nullptr;
    return env->NewStringUTF(output.data());
}

extern "C" JNIEXPORT void JNICALL
Java_de_x0bubbuff_needlebub_runtime_NeedleNative_reset(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    release_model();
}
