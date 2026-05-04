#include <jni.h>
#include "studylib.h"

using namespace studylib;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_studydemo_NativeHelper_hello(JNIEnv *env, jclass clazz, jstring name) {
    const char *nameStr = env->GetStringUTFChars(name, nullptr);
    std::string result = hello(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jint JNICALL
Java_com_example_studydemo_NativeHelper_add(JNIEnv *env, jclass clazz, jint a, jint b) {
    return add(a, b);
}

JNIEXPORT jstring JNICALL
Java_com_example_studydemo_NativeHelper_getSystemInfo(JNIEnv *env, jclass clazz) {
    std::string info = getSystemInfo();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_studydemo_NativeHelper_getByteArray(JNIEnv *env, jclass clazz, jint size) {
    int len = 0;
    int8_t* bytes = createByteArray(size, &len);
    if (bytes == nullptr) {
        return nullptr;
    }
    jbyteArray byteArray = env->NewByteArray(len);
    if (byteArray == nullptr) {
        delete[] bytes;
        return nullptr;
    }
    env->SetByteArrayRegion(byteArray, 0, len, bytes);
    delete[] bytes;
    return byteArray;
}

JNIEXPORT jstring JNICALL
Java_com_example_studydemo_NativeHelper_processIntArray(JNIEnv *env, jclass clazz, jintArray array) {
    jsize len = env->GetArrayLength(array);
    jint *elements = env->GetIntArrayElements(array, nullptr);
    std::string result = processIntArray(elements, len);
    env->ReleaseIntArrayElements(array, elements, 0);
    return env->NewStringUTF(result.c_str());
}

}