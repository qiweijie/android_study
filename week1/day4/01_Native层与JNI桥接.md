# Day 4 上午：Native 层 & JNI 桥接

## 📚 学习目标
- 理解 Bionic libc 与 glibc 的差异
- 掌握 JNI 机制：Java ↔ Native 桥接原理
- 了解 Android Native 守护进程的架构
- 理解 Android linker 的动态链接机制

---

## 一、Bionic libc — Android 的 C 库

### 1.1 为什么不用 glibc

```
glibc vs Bionic 对比：

特性              glibc                  Bionic
─────────────────────────────────────────────────
大小             ~10MB                  ~1MB
许可证           LGPL（传染性）          BSD（宽松）
线程实现         NPTL                   基于 futex
DNS 解析         完整实现               简化版
locale 支持      完整                   极简
C++ 异常         完整支持               支持（配合 libc++）
POSIX 兼容       完整                   部分兼容
目标平台         Linux 通用             Android 专用
─────────────────────────────────────────────────

Android 选择 Bionic 的原因：
1. 体积小 → 适合移动设备
2. BSD 许可 → 不会"传染"到上层代码
3. 性能优化 → 针对 ARM 架构优化
4. Android 特有功能 → 系统属性、日志集成
```

### 1.2 Bionic 的特殊之处

```
Bionic 特有功能：

┌─────────────────────────────────────────────┐
│  1. 系统属性 (System Properties)             │
│     __system_property_get()                 │
│     __system_property_set()                 │
│     → 全局键值对，无需 Binder 即可读取       │
│                                             │
│  2. Android 日志                            │
│     __android_log_print()                   │
│     → 直接写入 logd                         │
│                                             │
│  3. fdsan (File Descriptor Sanitizer)       │
│     → 检测 fd 被错误关闭的 bug              │
│                                             │
│  4. Scudo 内存分配器                         │
│     → 替代 jemalloc，更安全                 │
│                                             │
│  5. 简化的 pthread 实现                      │
│     → 基于 Linux futex，轻量级              │
└─────────────────────────────────────────────┘
```

### 1.3 C++ 开发者注意事项

| 你在 Linux 上习惯的 | Bionic 的差异 |
|---------------------|-------------|
| `malloc/free` | 默认使用 Scudo 分配器 |
| `pthread_create` | 默认栈大小 1MB（glibc 8MB） |
| `dlopen/dlsym` | 有 namespace 隔离机制 |
| `printf` | 不支持 `%n` 格式符（安全） |
| `system()` | 行为可能受 SELinux 限制 |
| `locale` | 只支持 C/POSIX locale |

源码路径：
```
bionic/
├── libc/           # C 库实现
├── libm/           # 数学库
├── libdl/          # 动态链接库
├── linker/         # 动态链接器
└── tests/          # 测试用例
```

---

## 二、JNI — Java 与 Native 的桥梁

### 2.1 JNI 概述

```
JNI = Java Native Interface
    = Java 调用 C/C++ 代码的标准接口
    = Android Framework 中大量使用

为什么需要 JNI：
┌──────────────┐     JNI     ┌──────────────┐
│  Java 层     │ ◄══════════► │  Native 层   │
│  Framework   │             │  C/C++ 库     │
│  App 代码    │             │  系统服务     │
└──────────────┘             └──────────────┘

使用场景：
• Java 调用 C++ 高性能库（图像处理、加密）
• Java 调用已有的 C/C++ 代码
• 需要直接访问硬件或系统底层
• Framework 中 Java 层调用 Native 实现（如 SurfaceFlinger）
```

### 2.2 JNI 核心概念

#### JNIEnv — 每个线程的 JNI 环境

```cpp
// JNIEnv 是线程局部的，每个线程有自己的 JNIEnv
// 不能跨线程使用！

// 正确：在当前线程使用
void myFunction(JNIEnv* env, jobject obj) {
    jstring str = env->NewStringUTF("Hello");
}

// 错误：保存 JNIEnv 指针跨线程使用
static JNIEnv* savedEnv;  // ❌ 不要这样做！
```

#### JavaVM — 全局虚拟机引用

```cpp
// JavaVM 是全局的，整个进程只有一个
// 可以通过 JavaVM 获取当前线程的 JNIEnv

static JavaVM* g_jvm = nullptr;

// 在 JNI_OnLoad 中保存
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;  // ✅ 保存全局 JavaVM
    return JNI_VERSION_1_6;
}

// 在其他线程中获取 JNIEnv
void backgroundThread() {
    JNIEnv* env;
    g_jvm->AttachCurrentThread(&env, nullptr);
    // 使用 env...
    g_jvm->DetachCurrentThread();
}
```

#### JNI 引用管理

```
JNI 三种引用类型：

┌─────────────────────────────────────────────┐
│  Local Reference（局部引用）                 │
│  • 在 JNI 方法返回后自动释放                 │
│  • 默认创建的都是局部引用                    │
│  • 不能跨方法调用保存                        │
│  • 有数量限制（默认 512 个）                 │
│                                             │
│  Global Reference（全局引用）                │
│  • 需手动创建/释放                          │
│  • 可以跨线程、跨方法使用                    │
│  • 不释放就会泄漏                           │
│  env->NewGlobalRef(obj) / DeleteGlobalRef   │
│                                             │
│  Weak Global Reference（弱全局引用）         │
│  • 不阻止 GC 回收目标对象                   │
│  • 使用前需检查是否已被回收                  │
│  env->NewWeakGlobalRef(obj)                 │
└─────────────────────────────────────────────┘
```

### 2.3 JNI 方法注册

#### 方式一：静态注册（命名约定）

```cpp
// Java 声明
package com.example;
public class MyClass {
    public native String hello(String name);
}

// C++ 实现 — 方法名按规则拼接
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_MyClass_hello(JNIEnv* env, jobject thiz, jstring name) {
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    std::string result = "Hello, " + std::string(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);
    return env->NewStringUTF(result.c_str());
}
// 命名规则：Java_包名_类名_方法名（. 替换为 _）
```

#### 方式二：动态注册（推荐）

```cpp
// 定义方法映射表
static JNINativeMethod gMethods[] = {
    {"hello", "(Ljava/lang/String;)Ljava/lang/String;", (void*)native_hello},
    {"add",   "(II)I",                                   (void*)native_add},
};

// 在 JNI_OnLoad 中注册
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    jclass clazz = env->FindClass("com/example/MyClass");
    env->RegisterNatives(clazz, gMethods, sizeof(gMethods)/sizeof(gMethods[0]));

    return JNI_VERSION_1_6;
}

// Android Framework 大量使用动态注册
// 优点：不受命名约定限制，更灵活
```

### 2.4 JNI 数据类型映射

```
Java 类型   →   JNI 类型    →   C++ 类型
─────────────────────────────────────────
boolean        jboolean       uint8_t
byte           jbyte          int8_t
char           jchar          uint16_t
short          jshort         int16_t
int            jint           int32_t
long           jlong          int64_t
float          jfloat         float
double         jdouble        double
String         jstring        (需转换)
Object         jobject        (不透明指针)
int[]          jintArray      (需转换)
─────────────────────────────────────────

字符串转换：
  Java String → C: env->GetStringUTFChars(jstr, nullptr)
  C → Java String: env->NewStringUTF(cstr)
  用完必须释放:    env->ReleaseStringUTFChars(jstr, cstr)
```

### 2.5 JNI 与稳定性

JNI 是 Native Crash 的高发区：

| 常见问题 | 原因 | 后果 |
|---------|------|------|
| 忘记释放 Local Ref | 循环中大量创建 | `local reference table overflow` |
| 跨线程使用 JNIEnv | JNIEnv 线程局部 | SIGSEGV |
| 未检查异常 | Java 抛异常后继续调用 JNI | 未定义行为 |
| 字符串未释放 | GetStringUTFChars 后未 Release | 内存泄漏 |
| 全局引用未释放 | NewGlobalRef 后未 Delete | 内存泄漏 |

---

## 三、Android Native 守护进程

### 3.1 关键 Native 进程

```
Android 系统中的关键 Native 守护进程：

┌─────────────────────────────────────────────────────┐
│  进程名          语言    职责                         │
├─────────────────────────────────────────────────────┤
│  init            C++    1号进程，启动所有其他进程      │
│  servicemanager  C++    Binder 服务注册中心           │
│  surfaceflinger  C++    图形合成，管理所有 Surface     │
│  mediaserver     C++    音视频播放/录制服务            │
│  audioserver     C++    音频策略与混音                │
│  cameraserver    C++    相机 HAL 管理                │
│  logd            C++    日志守护进程                  │
│  vold            C++    存储卷管理（SD 卡、USB）       │
│  netd            C++    网络守护进程                  │
│  installd        C++    APK 安装辅助进程              │
│  lmkd            C       低内存杀手守护进程            │
│  debuggerd       C++    Crash 收集（Tombstone）       │
└─────────────────────────────────────────────────────┘
```

### 3.2 SurfaceFlinger 架构概览

```
SurfaceFlinger — 图形合成引擎（纯 C++）

App 进程                    system_server              SurfaceFlinger
┌────────────┐             ┌────────────┐             ┌────────────┐
│  Surface   │   Binder    │    WMS     │   Binder    │  合成引擎  │
│  Canvas    │ ──────────► │  窗口管理  │ ──────────► │  HWComposer│
│  draw()    │             │  布局计算  │             │  GPU/DPU   │
└────────────┘             └────────────┘             └────────────┘
      │                                                    │
      │ BufferQueue (共享内存)                               │
      └──────────────────────────────────────────────────► │
                                                           │
                                                    ┌──────┴──────┐
                                                    │   Display   │
                                                    │   屏幕显示  │
                                                    └─────────────┘

源码路径：
frameworks/native/services/surfaceflinger/
├── SurfaceFlinger.cpp       # 主类
├── Layer.cpp                # 图层管理
├── BufferQueueLayer.cpp     # 缓冲区队列
├── Scheduler/               # VSync 调度
└── CompositionEngine/       # 合成引擎
```

### 3.3 C++ 开发者的亲切感

作为 C++ 开发者，你会发现 Android Native 层大量使用：

| C++ 特性 | Android 中的应用 |
|---------|-----------------|
| 智能指针 `sp<>/wp<>` | 替代 std::shared_ptr/weak_ptr |
| RAII | Mutex::Autolock 自动解锁 |
| 虚函数/接口 | IBinder、IInterface 体系 |
| 模板 | LightRefBase、Singleton |
| 线程池 | ThreadPool in libbinder |
| 设计模式 | 工厂、观察者、代理模式 |

---

## 四、Linker — Android 动态链接器

### 4.1 Android linker 与 ld-linux 的差异

```
Android 的动态链接器：

/system/bin/linker64    (64-bit)
/system/bin/linker      (32-bit)

区别于 Linux 的 /lib64/ld-linux-x86-64.so.2

特殊机制：
1. Namespace 隔离 — 不同进程/库可见的 .so 不同
2. 灰名单机制 — 限制 App 直接使用平台私有库
3. VNDK (Vendor NDK) — 分离 vendor 和 system 的库依赖
```

### 4.2 .so 加载流程

```
App 调用 System.loadLibrary("mylib")

    │
    ▼
Java: Runtime.loadLibrary0()
    │
    ▼
Native: android_dlopen_ext()
    │
    ▼
linker: do_dlopen()
    ├── 查找 .so 文件路径
    │   └── /data/app/xxx/lib/arm64/libmylib.so
    ├── mmap 映射到内存
    ├── 符号解析 (relocation)
    │   └── 解析 .so 中引用的外部符号
    ├── 执行 .init_array (构造函数)
    └── 调用 JNI_OnLoad() (如果有)
```

### 4.3 Namespace 隔离

```
Android 8.0+ 的 linker namespace：

┌─────────────────────────────────────────┐
│  App namespace                          │
│  可见: NDK 公开库 (libc, liblog, ...)   │
│  不可见: 平台私有库                      │
├─────────────────────────────────────────┤
│  Platform namespace                     │
│  可见: 所有平台库                        │
│  framework 代码使用                     │
├─────────────────────────────────────────┤
│  Vendor namespace                       │
│  可见: VNDK 库 + vendor 私有库           │
│  HAL 实现使用                           │
└─────────────────────────────────────────┘

目的：
• 防止 App 使用未公开的平台库（稳定性保障）
• 允许平台库升级不影响 App
• 隔离 vendor 和 system 的库版本
```

### 4.4 常见链接问题

| 错误 | 原因 | 解决 |
|------|------|------|
| `dlopen failed: library not found` | .so 路径不在搜索范围 | 检查 namespace 配置 |
| `cannot locate symbol "xxx"` | 依赖库未加载 | 检查 shared_libs 声明 |
| `TEXTREL` 警告 | .so 中有文本段重定位 | 编译时加 `-fPIC` |
| `UnsatisfiedLinkError` | JNI 方法未注册 | 检查方法签名 |

---

## 五、HAL 与 HIDL / Stable AIDL

### 5.1 HAL 层概述

```
HAL = Hardware Abstraction Layer = 硬件抽象层

App
 │
Framework (Java)
 │ JNI
Native Framework (C++)
 │ Binder/HIDL/AIDL
HAL (C/C++)              ← 厂商实现
 │
Kernel Driver
 │
Hardware

HAL 的作用：
• 统一硬件接口，屏蔽芯片差异
• 允许厂商不公开驱动源码（HAL 为用户空间）
• 接口稳定，框架和 HAL 可独立升级
```

### 5.2 HIDL vs Stable AIDL

```
HIDL (HAL Interface Definition Language) — Android 8-12
Stable AIDL — Android 11+ (推荐，逐步替代 HIDL)

┌──────────────────────────────────────────┐
│  HIDL (已冻结，不再新增)                  │
│  • .hal 文件定义接口                     │
│  • 自动生成 C++ 代码                     │
│  • 支持 passthrough / binderized 模式     │
│  • Android 8-12 时代的主流               │
├──────────────────────────────────────────┤
│  Stable AIDL (推荐)                      │
│  • .aidl 文件定义接口（与 App AIDL 类似） │
│  • 自动生成 C++/Java/Rust 代码            │
│  • 接口版本化，保证前后兼容               │
│  • Android 11+ 推荐方式                  │
└──────────────────────────────────────────┘
```

---

## ✅ 本节自检清单

- [ ] 能说出 Bionic 与 glibc 的 3 个关键差异
- [ ] 理解 JNIEnv 和 JavaVM 的区别和使用场景
- [ ] 知道 JNI 的静态注册和动态注册两种方式
- [ ] 理解 JNI 三种引用类型及其生命周期
- [ ] 能列举 3 个 Android 关键 Native 守护进程
- [ ] 理解 linker namespace 的隔离机制
- [ ] 了解 HIDL 和 Stable AIDL 的用途

---

## 💡 思考题

1. 为什么 JNIEnv 不能跨线程使用？它的底层实现是什么？
2. Android 为什么要设计 linker namespace？如果没有它会怎样？
3. Framework 中哪些场景会通过 JNI 调用 Native 代码？
4. Native 守护进程崩溃后，系统如何恢复？（提示：init.rc 中的 `restart`）
