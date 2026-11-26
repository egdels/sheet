// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>

#include <string>
#include <vector>

#include "net.h"

#ifdef __ANDROID__
#include <android/asset_manager_jni.h>
#endif

namespace {

const char* const kInputBlob = "in0";

struct ScopedUtf {
    JNIEnv* env;
    jstring string;
    const char* chars;
    ScopedUtf(JNIEnv* e, jstring s) : env(e), string(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~ScopedUtf() {
        if (chars) env->ReleaseStringUTFChars(string, chars);
    }
};

ncnn::Net* newNet(jint threads) {
    ncnn::Net* net = new ncnn::Net();
    net->opt.num_threads = threads < 1 ? 1 : threads;
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_packed = false;
    net->opt.use_fp16_storage = false;
    net->opt.use_fp16_arithmetic = false;
    net->opt.use_bf16_packed = false;
    net->opt.use_bf16_storage = false;
    net->opt.use_int8_packed = false;
    net->opt.use_int8_storage = false;
    net->opt.use_int8_arithmetic = false;
    net->opt.use_int8_inference = false;
    net->opt.lightmode = true;
    return net;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_de_schliweb_sheet_core_ncnn_NcnnNet_nativeLoadAssets(
        JNIEnv* env, jclass, jobject assetManager, jstring paramPath, jstring binPath, jint threads) {
#ifdef __ANDROID__
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    ScopedUtf param(env, paramPath);
    ScopedUtf bin(env, binPath);
    if (!manager || !param.chars || !bin.chars) return 0;
    ncnn::Net* net = newNet(threads);
    if (net->load_param(manager, param.chars) != 0 || net->load_model(manager, bin.chars) != 0) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
#else
    (void)env;
    (void)assetManager;
    (void)paramPath;
    (void)binPath;
    (void)threads;
    return 0;
#endif
}

JNIEXPORT jlong JNICALL Java_de_schliweb_sheet_core_ncnn_NcnnNet_nativeLoadFiles(
        JNIEnv* env, jclass, jstring paramPath, jstring binPath, jint threads) {
    ScopedUtf param(env, paramPath);
    ScopedUtf bin(env, binPath);
    if (!param.chars || !bin.chars) return 0;
    ncnn::Net* net = newNet(threads);
    if (net->load_param(param.chars) != 0 || net->load_model(bin.chars) != 0) {
        delete net;
        return 0;
    }
    return reinterpret_cast<jlong>(net);
}

JNIEXPORT void JNICALL Java_de_schliweb_sheet_core_ncnn_NcnnNet_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}

JNIEXPORT jobjectArray JNICALL Java_de_schliweb_sheet_core_ncnn_NcnnNet_nativeRun(
        JNIEnv* env, jclass, jlong handle, jfloatArray input, jint channels, jint height, jint width,
        jobjectArray outputNames, jintArray dims) {
    ncnn::Net* net = reinterpret_cast<ncnn::Net*>(handle);
    const jsize outputCount = env->GetArrayLength(outputNames);
    const size_t plane = static_cast<size_t>(height) * static_cast<size_t>(width);

    ncnn::Mat in(width, height, channels);
    if (in.empty()) {
        throwIllegalState(env, "out of memory for the network input");
        return nullptr;
    }
    for (int c = 0; c < channels; c++) {
        env->GetFloatArrayRegion(input, static_cast<jsize>(c * plane), static_cast<jsize>(plane), in.channel(c));
    }

    ncnn::Extractor extractor = net->create_extractor();
    if (extractor.input(kInputBlob, in) != 0) {
        throwIllegalState(env, "network has no input named in0");
        return nullptr;
    }

    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray result = env->NewObjectArray(outputCount, floatArrayClass, nullptr);
    std::vector<jint> shape(static_cast<size_t>(outputCount) * 3);
    for (jsize i = 0; i < outputCount; i++) {
        jstring nameString = static_cast<jstring>(env->GetObjectArrayElement(outputNames, i));
        ncnn::Mat out;
        {
            ScopedUtf name(env, nameString);
            if (extractor.extract(name.chars, out) != 0 || out.empty()) {
                throwIllegalState(env, std::string("network produced no output named ") + name.chars);
                return nullptr;
            }
        }
        env->DeleteLocalRef(nameString);

        const int outChannels = out.dims >= 3 ? out.c : 1;
        const int outHeight = out.dims >= 3 ? out.h * out.d : (out.dims == 2 ? out.h : 1);
        const int outWidth = out.w;
        const jsize outPlane = static_cast<jsize>(outHeight) * outWidth;
        jfloatArray values = env->NewFloatArray(outChannels * outPlane);
        if (!values) return nullptr;
        if (out.dims >= 3) {
            for (int c = 0; c < outChannels; c++) {
                env->SetFloatArrayRegion(values, c * outPlane, outPlane, out.channel(c));
            }
        } else {
            env->SetFloatArrayRegion(values, 0, outPlane, static_cast<const float*>(out));
        }
        env->SetObjectArrayElement(result, i, values);
        env->DeleteLocalRef(values);
        shape[i * 3] = outChannels;
        shape[i * 3 + 1] = outHeight;
        shape[i * 3 + 2] = outWidth;
    }
    env->SetIntArrayRegion(dims, 0, outputCount * 3, shape.data());
    return result;
}

}
