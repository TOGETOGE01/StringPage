#include <jni.h>
#include "CDSPResampler.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kBlock = 8192;
struct State {
    int channels;
    std::array<std::unique_ptr<r8b::CDSPResampler24>, 2> converters;
    std::array<std::vector<double>, 2> input;
    std::vector<float> interleaved;
    State(int sourceRate, int channelCount) : channels(channelCount) {
        for (int c = 0; c < channels; ++c) {
            converters[c] = std::make_unique<r8b::CDSPResampler24>(sourceRate, 44100, kBlock);
            input[c].resize(kBlock);
        }
    }
};
std::mutex statesMutex;
std::unordered_map<jlong, std::unique_ptr<State>> states;
jlong nextId = 1;
void error(JNIEnv* env, const char* type, const char* message) {
    auto cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stringpage_app_rse_NativeSourceRateConverter_create(JNIEnv* env, jobject, jint rate, jint channels) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2) {
        error(env, "java/lang/IllegalArgumentException", "Invalid sample format"); return 0;
    }
    try {
        auto state = std::make_unique<State>(rate, channels);
        std::lock_guard<std::mutex> lock(statesMutex);
        const auto id = nextId++;
        states.emplace(id, std::move(state));
        return id;
    } catch (const std::bad_alloc&) { error(env, "java/lang/OutOfMemoryError", "Sample rate converter allocation failed"); }
    catch (const std::exception& ex) { error(env, "java/lang/IllegalStateException", ex.what()); }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stringpage_app_rse_NativeSourceRateConverter_process(JNIEnv* env, jobject, jlong id,
    jfloatArray pcm, jint offset, jint frames) {
    try {
        std::lock_guard<std::mutex> lock(statesMutex);
        const auto found = states.find(id);
        if (found == states.end()) throw std::logic_error("Sample rate converter is closed");
        auto& state = *found->second;
        const auto count = frames * static_cast<int64_t>(state.channels);
        if (frames < 1 || frames > kBlock || offset < 0 ||
            (pcm && (offset > env->GetArrayLength(pcm) || count > env->GetArrayLength(pcm) - offset))) {
            throw std::invalid_argument("Invalid PCM block");
        }
        state.interleaved.resize(count);
        if (pcm) {
            env->GetFloatArrayRegion(pcm, offset, count, state.interleaved.data());
            if (env->ExceptionCheck()) return nullptr;
        } else std::fill(state.interleaved.begin(), state.interleaved.end(), 0.f);
        std::array<double*, 2> output{};
        int outputFrames = -1;
        for (int c = 0; c < state.channels; ++c) {
            for (int i = 0; i < frames; ++i) state.input[c][i] = state.interleaved[i * state.channels + c];
            const int written = state.converters[c]->process(state.input[c].data(), frames, output[c]);
            if (written < 0 || written > 262144 || (c && written != outputFrames)) {
                throw std::runtime_error("Invalid converter output length");
            }
            outputFrames = written;
        }
        state.interleaved.resize(outputFrames * state.channels);
        for (int i = 0; i < outputFrames; ++i) for (int c = 0; c < state.channels; ++c)
            state.interleaved[i * state.channels + c] = static_cast<float>(output[c][i]);
        auto result = env->NewFloatArray(static_cast<jsize>(state.interleaved.size()));
        if (result && !state.interleaved.empty())
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(state.interleaved.size()), state.interleaved.data());
        return result;
    } catch (const std::bad_alloc&) { error(env, "java/lang/OutOfMemoryError", "PCM conversion allocation failed"); }
    catch (const std::exception& ex) { error(env, "java/lang/IllegalStateException", ex.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stringpage_app_rse_NativeSourceRateConverter_destroy(JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(statesMutex);
    states.erase(id);
}
