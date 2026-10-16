#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

#include "rnnoise.h"

namespace {

constexpr int kFrameSamples = 480;
constexpr int kFrameBytes = kFrameSamples * sizeof(int16_t);

// One owner and one pair of scratch buffers per Kotlin processor. All native
// calls are serialized by that processor's lock; the capture callback allocates
// no native heap memory and never stores PCM outside this object.
struct Processor {
    DenoiseState* state = nullptr;
    float input[kFrameSamples] = {};
    float output[kFrameSamples] = {};
};

void throwException(JNIEnv* env, const char* type, const char* message) {
    jclass exceptionClass = env->FindClass(type);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

Processor* getProcessor(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        throwException(env, "java/lang/IllegalStateException", "RNNoise processor is closed");
        return nullptr;
    }
    return reinterpret_cast<Processor*>(static_cast<intptr_t>(handle));
}

bool resetProcessor(JNIEnv* env, Processor* processor) {
    // rnnoise_init clears the full DenoiseState, including recurrent/pitch,
    // analysis, synthesis and look-ahead history, then binds the static model.
    std::memset(processor->input, 0, sizeof(processor->input));
    std::memset(processor->output, 0, sizeof(processor->output));
    if (rnnoise_init(processor->state, nullptr) != 0) {
        throwException(env, "java/lang/IllegalStateException", "Unable to initialize RNNoise model");
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kuma_motointercom_RnNoiseProcessor_nativeCreate(JNIEnv* env, jobject) {
    if (rnnoise_get_frame_size() != kFrameSamples) {
        throwException(env, "java/lang/IllegalStateException", "RNNoise model has an unsupported frame size");
        return 0;
    }
    auto* processor = new (std::nothrow) Processor;
    if (processor == nullptr) {
        throwException(env, "java/lang/OutOfMemoryError", "Unable to allocate RNNoise processor");
        return 0;
    }
    processor->state = static_cast<DenoiseState*>(std::malloc(rnnoise_get_size()));
    if (processor->state == nullptr) {
        delete processor;
        throwException(env, "java/lang/OutOfMemoryError", "Unable to allocate RNNoise state");
        return 0;
    }
    if (!resetProcessor(env, processor)) {
        std::free(processor->state);
        delete processor;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(processor));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kuma_motointercom_RnNoiseProcessor_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint length) {
    Processor* processor = getProcessor(env, handle);
    if (processor == nullptr) return 0.0f;
    void* base = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || offset < 0 || length != kFrameBytes || capacity < length ||
            offset > capacity - length) {
        throwException(env, "java/lang/IllegalArgumentException", "RNNoise requires one direct 960-byte PCM16 frame");
        return 0.0f;
    }
    auto* bytes = static_cast<uint8_t*>(base) + offset;
    for (int i = 0; i < kFrameSamples; ++i) {
        int16_t sample;
        // memcpy also supports an unaligned position or sliced direct buffer.
        std::memcpy(&sample, bytes + i * sizeof(sample), sizeof(sample));
        // RNNoise expects PCM16-scale floats, not normalized [-1, 1] floats.
        processor->input[i] = static_cast<float>(sample);
    }
    const float vad = rnnoise_process_frame(processor->state, processor->output, processor->input);
    if (!std::isfinite(vad)) {
        resetProcessor(env, processor);
        throwException(env, "java/lang/IllegalStateException", "RNNoise returned a non-finite voice probability");
        return 0.0f;
    }
    // Validate before writing: on failure the caller's PCM remains unchanged.
    for (float sample : processor->output) {
        if (!std::isfinite(sample)) {
            resetProcessor(env, processor);
            throwException(env, "java/lang/IllegalStateException", "RNNoise returned non-finite PCM");
            return 0.0f;
        }
    }
    for (int i = 0; i < kFrameSamples; ++i) {
        const float value = processor->output[i];
        const int16_t sample = value >= 32767.0f ? INT16_MAX :
                value <= -32768.0f ? INT16_MIN : static_cast<int16_t>(std::lrintf(value));
        std::memcpy(bytes + i * sizeof(sample), &sample, sizeof(sample));
    }
    return vad;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kuma_motointercom_RnNoiseProcessor_nativeReset(JNIEnv* env, jobject, jlong handle) {
    Processor* processor = getProcessor(env, handle);
    if (processor != nullptr) resetProcessor(env, processor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kuma_motointercom_RnNoiseProcessor_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    auto* processor = reinterpret_cast<Processor*>(static_cast<intptr_t>(handle));
    rnnoise_destroy(processor->state);
    delete processor;
}
