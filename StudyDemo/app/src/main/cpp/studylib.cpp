#include "studylib.h"
#include <cstring>
#include <android/log.h>
#include <unistd.h>
#include <sys/sysinfo.h>

#define LOG_TAG "StudyLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace studylib {

static const char* getAbiName() {
#ifdef __aarch64__
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "unknown";
#endif
}

std::string hello(const char* name) {
    LOGD("hello() called with: %s", name);
    return "Hello, " + std::string(name) + " from C++!";
}

int add(int a, int b) {
    LOGD("add() called: %d + %d, PID=%d, TID=%d", a, b, getpid(), gettid());
    return a + b;
}

std::string getSystemInfo() {
    char info[512];
    snprintf(info, sizeof(info),
             "=== Native System Info ===\n"
             "PID: %d\n"
             "TID: %d\n"
             "Pointer size: %zu bytes\n"
             "Page size: %ld bytes\n"
             "Android ABI: %s",
             getpid(), gettid(),
             sizeof(void*),
             sysconf(_SC_PAGESIZE),
             getAbiName());
    LOGD("getSystemInfo() called");
    return info;
}

int8_t* createByteArray(int size, int* outLen) {
    if (size <= 0 || outLen == nullptr) {
        if (outLen) *outLen = 0;
        return nullptr;
    }
    int8_t* bytes = new int8_t[size];
    for (int i = 0; i < size; i++) {
        bytes[i] = static_cast<int8_t>(i % 256);
    }
    *outLen = size;
    LOGD("createByteArray() created array of size %d", size);
    return bytes;
}

IntArrayResult calculateIntArrayStats(const int32_t* array, int len) {
    IntArrayResult result = {0, 0, 0, 0.0};
    if (array == nullptr || len <= 0) {
        return result;
    }
    int64_t sum = 0;
    result.min = array[0];
    result.max = array[0];
    for (int i = 0; i < len; i++) {
        sum += array[i];
        if (array[i] < result.min) result.min = array[i];
        if (array[i] > result.max) result.max = array[i];
    }
    result.sum = static_cast<int32_t>(sum);
    result.avg = static_cast<double>(sum) / len;
    LOGD("calculateIntArrayStats() processed %d elements", len);
    return result;
}

std::string processIntArray(const int32_t* array, int len) {
    IntArrayResult r = calculateIntArrayStats(array, len);
    char result[256];
    snprintf(result, sizeof(result),
             "Array[%d]: sum=%d, min=%d, max=%d, avg=%.2f",
             len, r.sum, r.min, r.max, r.avg);
    return result;
}

void triggerNativeCrash(int crashType) {
    LOGD("triggerNativeCrash() called with type: %d", crashType);
    
    switch (crashType) {
        case 1: {
            // 空指针解引用 - NULL pointer dereference
            LOGD("Crash type 1: NULL pointer dereference");
            int* nullPtr = nullptr;
            *nullPtr = 42;  // 这会导致 SIGSEGV
            break;
        }
        case 2: {
            // 数组越界访问 - Array out of bounds
            LOGD("Crash type 2: Array out of bounds access");
            int arr[5] = {1, 2, 3, 4, 5};
            int value = arr[100];  // 越界访问
            LOGD("value: %d", value);  // 可能不会立即崩溃，但访问非法内存
            break;
        }
        case 3: {
            // 整数除零 - Division by zero
            LOGD("Crash type 3: Division by zero");
            int a = 10;
            int b = 0;
            int result = a / b;  // 这会导致 SIGFPE
            LOGD("result: %d", result);
            break;
        }
        case 4: {
            // Use-after-free - 访问已释放的内存
            LOGD("Crash type 4: Use-after-free");
            int* ptr = new int(100);
            delete ptr;
            *ptr = 200;  // 访问已释放的内存，会导致 SIGSEGV
            break;
        }
        default: {
            // 默认: 直接崩溃
            LOGD("Crash type default: Forcing crash");
            *(volatile int*)0 = 0;  // 直接访问地址 0
            break;
        }
    }
}

}