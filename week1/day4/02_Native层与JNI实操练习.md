# Day 4 下午：Native 层 & JNI 实操练习

## 🎯 实操目标
- 编写 JNI 模块：在 App 中添加 Native 方法，用 C++ 实现
- 学习 Android.bp / CMake 构建系统
- 阅读 SurfaceFlinger 源码关键部分
- 了解 HIDL / Stable AIDL 的接口定义

---

## 实验一：编写 JNI 模块

### 1.1 创建支持 NDK 的 Android 项目

在 Android Studio 中创建新项目时选择 **Native C++** 模板，或在现有项目中添加 NDK 支持。

### 1.2 方式一：CMake 构建（App 开发常用）

#### CMakeLists.txt

```cmake
# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.18.1)
project("jnidemo")

add_library(jnidemo SHARED
    native-lib.cpp
)

# 链接 Android 日志库
find_library(log-lib log)
target_link_libraries(jnidemo ${log-lib})
```

#### C++ 实现

```cpp
// app/src/main/cpp/native-lib.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>

#define TAG "JNI-Demo"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ===== 示例 1：基本类型传递 =====
extern "C" JNIEXPORT jint JNICALL
Java_com_example_jnidemo_NativeHelper_add(
        JNIEnv* env, jclass clazz, jint a, jint b) {
    LOGD("add() called: %d + %d, PID=%d, TID=%d", a, b, getpid(), gettid());
    return a + b;
}

// ===== 示例 2：字符串处理 =====
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_jnidemo_NativeHelper_getSystemInfo(
        JNIEnv* env, jclass clazz) {
    char info[256];
    snprintf(info, sizeof(info),
             "PID: %d\nTID: %d\nPointer size: %zu bytes",
             getpid(), gettid(), sizeof(void*));
    LOGD("getSystemInfo() called");
    return env->NewStringUTF(info);
}

// ===== 示例 3：数组操作 =====
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_jnidemo_NativeHelper_sortArray(
        JNIEnv* env, jclass clazz, jintArray input) {
    jsize len = env->GetArrayLength(input);
    jint* elements = env->GetIntArrayElements(input, nullptr);

    // 用 C++ STL 排序
    std::sort(elements, elements + len);

    // 创建新数组返回
    jintArray result = env->NewIntArray(len);
    env->SetIntArrayRegion(result, 0, len, elements);

    // 释放原数组引用
    env->ReleaseIntArrayElements(input, elements, JNI_ABORT);

    LOGD("sortArray() sorted %d elements", len);
    return result;
}

// ===== 示例 4：回调 Java 方法 =====
extern "C" JNIEXPORT void JNICALL
Java_com_example_jnidemo_NativeHelper_callbackDemo(
        JNIEnv* env, jclass clazz, jobject callback) {
    // 获取 Java 回调接口的方法 ID
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onResult = env->GetMethodID(callbackClass, "onResult", "(Ljava/lang/String;)V");

    if (onResult == nullptr) {
        LOGD("Method onResult not found!");
        return;
    }

    // 模拟耗时操作
    usleep(500000);  // 500ms

    // 回调 Java
    jstring result = env->NewStringUTF("Native 处理完成！");
    env->CallVoidMethod(callback, onResult, result);
    env->DeleteLocalRef(result);

    LOGD("callbackDemo() completed");
}

// ===== 示例 5：故意触发 Native Crash（测试用） =====
extern "C" JNIEXPORT void JNICALL
Java_com_example_jnidemo_NativeHelper_triggerCrash(
        JNIEnv* env, jclass clazz, jint type) {
    switch (type) {
        case 0: {
            // 空指针解引用 → SIGSEGV
            LOGD("Triggering null pointer dereference...");
            int* p = nullptr;
            *p = 42;
            break;
        }
        case 1: {
            // 栈溢出 → SIGSEGV (stack overflow)
            LOGD("Triggering stack overflow...");
            volatile char buf[1024 * 1024 * 8];  // 8MB
            buf[0] = 0;
            break;
        }
        case 2: {
            // abort → SIGABRT
            LOGD("Triggering abort...");
            abort();
            break;
        }
    }
}
```

#### Java 层声明

```java
// NativeHelper.java
public class NativeHelper {
    static { System.loadLibrary("jnidemo"); }

    public static native int add(int a, int b);
    public static native String getSystemInfo();
    public static native int[] sortArray(int[] input);
    public static native void callbackDemo(NativeCallback callback);
    public static native void triggerCrash(int type);

    public interface NativeCallback {
        void onResult(String result);
    }
}
```

### 1.3 方式二：动态注册（Framework 风格）

```cpp
// 动态注册示例 — Android Framework 推荐方式
#include <jni.h>
#include <android/log.h>

#define TAG "JNI-Dynamic"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static jint native_add(JNIEnv* env, jclass clazz, jint a, jint b) {
    return a + b;
}

static jstring native_getInfo(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("Dynamic registration works!");
}

// 方法映射表
static JNINativeMethod gMethods[] = {
    {"add",      "(II)I",                  (void*)native_add},
    {"getInfo",  "()Ljava/lang/String;",   (void*)native_getInfo},
};

// JNI_OnLoad — 库加载时自动调用
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/example/jnidemo/NativeHelper");
    if (clazz == nullptr) {
        LOGD("FindClass failed!");
        return JNI_ERR;
    }

    int count = sizeof(gMethods) / sizeof(gMethods[0]);
    if (env->RegisterNatives(clazz, gMethods, count) < 0) {
        LOGD("RegisterNatives failed!");
        return JNI_ERR;
    }

    LOGD("JNI_OnLoad: registered %d methods", count);
    return JNI_VERSION_1_6;
}
```

### 1.4 运行和验证

```bash
# 查看 JNI 日志
adb logcat -s "JNI-Demo:D" "JNI-Dynamic:D"

# 验证 .so 是否正确加载
adb shell cat /proc/$(adb shell pidof com.example.jnidemo)/maps | grep jnidemo
```

---

## 实验二：Android.bp 构建系统（Soong）

### 2.1 Android.bp 基础

Android.bp 是 AOSP 的构建配置文件（替代旧的 Android.mk）。

```
// 编译一个共享库
cc_library_shared {
    name: "libmy_native",
    srcs: ["my_native.cpp"],

    // 依赖其他共享库
    shared_libs: [
        "liblog",
        "libutils",
        "libbinder",
    ],

    // C++ 编译选项
    cflags: [
        "-Wall",
        "-Werror",
        "-Wno-unused-parameter",
    ],

    // C++ 标准
    cppflags: ["-std=c++17"],
}
```

### 2.2 常用模块类型

```
模块类型                  用途                  输出
────────────────────────────────────────────────
cc_library_shared        共享库                libxxx.so
cc_library_static        静态库                libxxx.a
cc_library               共享+静态             两者都生成
cc_binary                可执行文件            xxx
cc_library_headers       仅头文件              无
cc_defaults              共享默认配置          无
cc_test                  测试模块              xxx_test
java_library             Java 库               xxx.jar
android_app              Android App           xxx.apk
```

### 2.3 一个完整的 Android.bp 示例

```
// 系统服务 Native 库示例
cc_library_shared {
    name: "libmy_system_service",
    srcs: [
        "MyService.cpp",
        "MyServiceImpl.cpp",
    ],
    shared_libs: [
        "libbase",           // Android base 工具库
        "liblog",            // 日志
        "libutils",          // Android 工具类
        "libbinder",         // Binder IPC
        "libcutils",         // C 工具
        "libhidlbase",       // HIDL 基础
    ],
    header_libs: [
        "libhardware_headers",
    ],
    cflags: [
        "-Wall",
        "-Werror",
    ],
    // 安装到 system 分区
    vendor: false,
}

// 对应的可执行文件（守护进程）
cc_binary {
    name: "my_service_daemon",
    srcs: ["main.cpp"],
    shared_libs: [
        "libmy_system_service",
        "liblog",
        "libbinder",
    ],
    init_rc: ["my_service_daemon.rc"],  // init.rc 配置
}
```

---

## 实验三：阅读 SurfaceFlinger 源码

### 3.1 源码位置

```
frameworks/native/services/surfaceflinger/
├── main_surfaceflinger.cpp   # 入口 main 函数
├── SurfaceFlinger.cpp        # 核心类（C++ 开发者重点阅读）
├── SurfaceFlinger.h
├── Layer.cpp                 # 图层管理
├── BufferLayer.cpp           # 缓冲区图层
├── Client.cpp                # Binder 客户端接口
├── Scheduler/
│   ├── VsyncSchedule.cpp     # VSync 调度
│   └── Scheduler.cpp
└── CompositionEngine/
    └── src/Output.cpp        # 合成输出
```

### 3.2 入口 main 函数

```cpp
// main_surfaceflinger.cpp
int main(int, char**) {
    // 1. 设置进程优先级
    setpriority(PRIO_PROCESS, 0, PRIORITY_URGENT_DISPLAY);
    set_sched_policy(0, SP_FOREGROUND);

    // 2. 初始化 Binder
    sp<ProcessState> ps(ProcessState::self());
    ps->setThreadPoolMaxThreadCount(4);

    // 3. 创建 SurfaceFlinger 实例
    sp<SurfaceFlinger> flinger = surfaceflinger::createSurfaceFlinger();

    // 4. 初始化
    flinger->init();

    // 5. 注册为 Binder 服务
    sp<IServiceManager> sm(defaultServiceManager());
    sm->addService(String16(SurfaceFlinger::getServiceName()), flinger);

    // 6. 启动 Binder 线程池
    ps->startThreadPool();

    // 7. 运行（进入主循环）
    flinger->run();

    return 0;
}
```

### 3.3 关键阅读指引

```
C++ 开发者重点关注：

1. 智能指针的使用
   sp<SurfaceFlinger> flinger  → 强引用
   wp<Layer> layer             → 弱引用
   → 对比你熟悉的 std::shared_ptr / std::weak_ptr

2. Binder 服务端实现
   class SurfaceFlinger : public BnSurfaceComposer
   → BnXxx = Binder Native 端 (Server)
   → BpXxx = Binder Proxy 端 (Client)

3. 消息循环
   SurfaceFlinger::run() → waitForEvent() → 处理 VSync
   → 类似 epoll 事件循环

4. 锁的使用
   mutable Mutex mStateLock;
   Mutex::Autolock lock(mStateLock);
   → RAII 风格自动锁
```

---

## 实验四：HIDL / Stable AIDL 了解

### 4.1 HIDL 接口示例

```
// hardware/interfaces/power/1.0/IPower.hal
package android.hardware.power@1.0;

interface IPower {
    setInteractive(bool interactive);
    powerHint(PowerHint hint, int32_t data);
    setFeature(Feature feature, bool activate);
    getPlatformLowPowerStats() generates (vec<PowerStatePlatformSleepState> retval);
};
```

### 4.2 Stable AIDL 接口示例

```
// hardware/interfaces/power/aidl/android/hardware/power/IPower.aidl
package android.hardware.power;

@VintfStability
interface IPower {
    void setMode(in Mode type, in boolean enabled);
    boolean isModeSupported(in Mode type);
    void setBoost(in Boost type, in int durationMs);
    boolean isBoostSupported(in Boost type);
}
```

### 4.3 查看设备上的 HAL 服务

```bash
# 列出所有 HIDL 服务
adb shell lshal

# 列出所有 Stable AIDL HAL 服务
adb shell service list | grep -i "hal"

# 查看特定 HAL 服务信息
adb shell lshal debug android.hardware.power@1.0::IPower/default
```

---

## ✅ 实操检查清单

- [ ] 成功编写 JNI 方法并在 App 中调用
- [ ] 能通过 Logcat 看到 Native 层的日志输出
- [ ] 理解静态注册和动态注册的区别
- [ ] 能读懂 Android.bp 中的 cc_library_shared 配置
- [ ] 阅读了 SurfaceFlinger 的 main 函数和初始化流程
- [ ] 执行 `adb shell lshal` 查看了 HAL 服务列表
- [ ] （扩展）通过 JNI 触发 Native Crash 并查看 Tombstone

---

## 📝 实操笔记模板

```
=== Day 4 下午实操笔记 ===

1. JNI 实验结果：
   - add() 调用结果: ______
   - Native 层 PID/TID: ______
   - .so 加载路径: ______

2. Android.bp 学习：
   - cc_library_shared 的必要字段: ______
   - shared_libs 与 static_libs 的区别: ______

3. SurfaceFlinger 源码阅读：
   - main 函数中 Binder 线程池大小: ______
   - 智能指针用法对比 std::shared_ptr: ______

4. HAL 服务观察：
   - 设备上的 HAL 服务数量: ______
   - Power HAL 使用的接口: HIDL / AIDL

5. C++ 关联知识：
   - Android sp<> 类似于: ______
   - Mutex::Autolock 类似于: ______
```
