#include <jni.h>

#include <algorithm>
#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"

namespace {
AudioEngine gEngine;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_start(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string soundFontPath(chars);
    env->ReleaseStringUTFChars(path, chars);
    return gEngine.start(soundFontPath) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_stop(JNIEnv * /*env*/, jobject /*thiz*/) {
    gEngine.stop();
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_setPlaying(JNIEnv * /*env*/, jobject /*thiz*/,
                                                   jboolean playing) {
    gEngine.setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_sendMidi(JNIEnv * /*env*/, jobject /*thiz*/, jint status,
                                                 jint data1, jint data2) {
    const uint32_t message = (static_cast<uint32_t>(status) & 0xFF) |
                             ((static_cast<uint32_t>(data1) & 0x7F) << 8) |
                             ((static_cast<uint32_t>(data2) & 0x7F) << 16);
    gEngine.sendMidi(message);
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_sendMidiToBank(JNIEnv * /*env*/, jobject /*thiz*/, jint bank,
                                                       jint status, jint data1, jint data2) {
    const uint32_t message = (static_cast<uint32_t>(status) & 0xFF) |
                             ((static_cast<uint32_t>(data1) & 0x7F) << 8) |
                             ((static_cast<uint32_t>(data2) & 0x7F) << 16) |
                             ((static_cast<uint32_t>(bank) & 0x0F) << 24);
    gEngine.sendMidi(message);
}

JNIEXPORT jintArray JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_stats(JNIEnv *env, jobject /*thiz*/) {
    const AudioEngine::Stats stats = gEngine.stats();
    const jint values[] = {stats.sampleRate, stats.framesPerBurst, stats.bufferFrames,
                           stats.xruns, stats.exclusive, stats.droppedMessages,
                           stats.latencyMillis};
    jintArray result = env->NewIntArray(7);
    env->SetIntArrayRegion(result, 0, 7, values);
    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_takePeak(JNIEnv * /*env*/, jobject /*thiz*/) {
    return gEngine.takePeak();
}

// ---- Looper ----------------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopSet(JNIEnv *env, jobject /*thiz*/, jintArray frames,
                                                jintArray messages, jint lengthFrames,
                                                jdouble framesPerBeat, jint beatsPerBar) {
    auto data = std::make_unique<LoopData>();
    data->lengthFrames = lengthFrames;
    data->framesPerBeat = framesPerBeat;
    data->beatsPerBar = beatsPerBar;
    const jsize count = std::min(env->GetArrayLength(frames), env->GetArrayLength(messages));
    std::vector<jint> frameValues(static_cast<size_t>(count));
    std::vector<jint> messageValues(static_cast<size_t>(count));
    env->GetIntArrayRegion(frames, 0, count, frameValues.data());
    env->GetIntArrayRegion(messages, 0, count, messageValues.data());
    data->events.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        data->events.push_back({frameValues[i], static_cast<uint32_t>(messageValues[i])});
    }
    gEngine.loop().setLoop(std::move(data));
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopStart(JNIEnv * /*env*/, jobject /*thiz*/,
                                                  jint countInBeats, jboolean record) {
    gEngine.loop().start(countInBeats, record == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopStop(JNIEnv * /*env*/, jobject /*thiz*/) {
    gEngine.loop().stop();
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopSetRecording(JNIEnv * /*env*/, jobject /*thiz*/,
                                                         jboolean recording) {
    gEngine.loop().setRecording(recording == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopSetMetronome(JNIEnv * /*env*/, jobject /*thiz*/,
                                                         jboolean on) {
    gEngine.loop().setMetronome(on == JNI_TRUE);
}

JNIEXPORT jlongArray JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopState(JNIEnv *env, jobject /*thiz*/) {
    const LoopSequencer::State state = gEngine.loop().state();
    const jlong values[] = {state.running ? 1 : 0, state.recording ? 1 : 0, state.position, state.length};
    jlongArray result = env->NewLongArray(4);
    env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_dhobson_lumi_audio_NativeSynth_loopDrainRecorded(JNIEnv *env, jobject /*thiz*/,
                                                          jlongArray out) {
    const jsize capacity = env->GetArrayLength(out);
    std::vector<int64_t> buffer(static_cast<size_t>(capacity));
    const int count = gEngine.loop().drainRecorded(buffer.data(), capacity);
    if (count > 0) {
        env->SetLongArrayRegion(out, 0, count, reinterpret_cast<const jlong *>(buffer.data()));
    }
    return count;
}

}  // extern "C"
