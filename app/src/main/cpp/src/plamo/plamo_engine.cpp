// JNI layer of plamo::Engine for nie.translator.rtranslator.plamo.PlamoEngine. Text crosses the boundary
// as UTF-8 byte arrays: JNI's "modified UTF-8" strings cannot carry characters outside the BMP.
#include <jni.h>

#include <string>
#include <vector>

#include "plamo_core.h"

namespace {

std::string bytes(JNIEnv *env, jbyteArray a) {
    jsize n = a ? env->GetArrayLength(a) : 0;
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_nie_translator_rtranslator_plamo_PlamoEngine_nativePerformanceCores(JNIEnv *, jclass) {
    return plamo::Engine::performance_cores();
}

JNIEXPORT jlong JNICALL
Java_nie_translator_rtranslator_plamo_PlamoEngine_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint threads, jint slotCtx) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    plamo::Engine *e = plamo::Engine::open(path, threads, slotCtx);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jbyteArray JNICALL
Java_nie_translator_rtranslator_plamo_PlamoEngine_nativeComplete(JNIEnv *env, jclass, jlong handle, jint slot, jbyteArray jprompt, jint maxTokens, jobjectArray jstops) {
    auto *e = reinterpret_cast<plamo::Engine *>(handle);
    if (!e) return nullptr;
    std::vector<std::string> stops;
    for (jsize i = 0, n = jstops ? env->GetArrayLength(jstops) : 0; i < n; i++) {
        auto b = (jbyteArray) env->GetObjectArrayElement(jstops, i);
        stops.push_back(bytes(env, b));
        env->DeleteLocalRef(b);
    }
    std::string out;
    if (!e->complete(slot, bytes(env, jprompt), maxTokens, stops, &out)) return nullptr;
    jbyteArray res = env->NewByteArray((jsize) out.size());
    if (res && !out.empty()) env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

JNIEXPORT jlongArray JNICALL
Java_nie_translator_rtranslator_plamo_PlamoEngine_nativeLastStats(JNIEnv *env, jclass, jlong handle) {
    auto *e = reinterpret_cast<plamo::Engine *>(handle);
    jlongArray res = env->NewLongArray(5);
    if (e && res) {
        jlong v[5];
        for (int i = 0; i < 5; i++) v[i] = (jlong) e->stats()[i];
        env->SetLongArrayRegion(res, 0, 5, v);
    }
    return res;
}

JNIEXPORT void JNICALL
Java_nie_translator_rtranslator_plamo_PlamoEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<plamo::Engine *>(handle);
}

}  // extern "C"
