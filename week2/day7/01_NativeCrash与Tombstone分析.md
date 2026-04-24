# Day 7 上午：Native Crash & Tombstone 分析

## 📚 学习目标
- 理解 Linux 信号机制：SIGSEGV / SIGABRT / SIGBUS / SIGFPE
- 掌握 Tombstone 文件的完整解析
- 了解 debuggerd 守护进程的工作流程
- 了解 ASan / HWASan 地址消毒器

---

## 一、信号机制

### 1.1 Native Crash 相关信号

```
信号          值    含义                    常见触发场景
──────────────────────────────────────────────────────────
SIGSEGV       11   段错误                  空指针解引用、访问已释放内存
  ├── SEGV_MAPERR    地址未映射             野指针
  └── SEGV_ACCERR    权限不足              写只读内存

SIGABRT        6   程序主动终止             assert 失败、abort()
                                           C++ 异常未捕获
                                           检测到堆损坏

SIGBUS         7   总线错误                 未对齐的内存访问
                                           映射文件被截断

SIGFPE         8   浮点异常                 整数除以零
                                           浮点溢出

SIGILL         4   非法指令                 执行了损坏的代码
                                           跳转到非代码区域

SIGTRAP        5   断点/陷阱               调试断点、__builtin_trap()
```

### 1.2 C++ 开发者关联

```
你在 Linux C++ 开发中遇到的 Crash，在 Android 上是一样的：

Linux:              Android:
────────────────    ────────────────────
core dump           → Tombstone 文件
GDB 分析            → addr2line / ndk-stack
valgrind            → ASan / HWASan
strace              → strace (需 root)
/proc/PID/maps      → Tombstone 中有 memory map
```

---

## 二、Tombstone 文件详解

### 2.1 Tombstone 结构

```
一个完整的 Tombstone 文件包含以下段落：

┌─────────────────────────────────────────┐
│ 1. 头部信息                             │
│    - 进程名、PID、TID                    │
│    - 信号类型和原因                      │
│    - 发生时间                           │
├─────────────────────────────────────────┤
│ 2. 寄存器状态                           │
│    - x0-x30 / r0-r15 (ARM)             │
│    - sp, pc, lr                         │
│    - fault address                      │
├─────────────────────────────────────────┤
│ 3. 调用栈 (backtrace)                   │
│    - 每帧的 PC 地址和库名                │
│    - 可符号化为函数名和行号              │
├─────────────────────────────────────────┤
│ 4. 栈内存 dump                          │
│    - 栈附近的原始内存                    │
├─────────────────────────────────────────┤
│ 5. 内存映射 (memory map)                │
│    - 所有已加载的 .so 和映射区域         │
│    - 类似 /proc/PID/maps                │
├─────────────────────────────────────────┤
│ 6. 其他线程的调用栈                      │
│    - 进程中所有线程的快照                │
└─────────────────────────────────────────┘
```

### 2.2 示例 Tombstone 解读

```
*** *** *** *** *** *** *** *** *** *** *** *** *** ***
Build fingerprint: 'google/...'
Revision: '0'
ABI: 'arm64'
Timestamp: 2024-01-15 14:30:00+0800
Process uptime: 3600s

pid: 12345, tid: 12345, name: myapp  >>> com.example.myapp <<<
uid: 10123
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
    ↑ 信号 11 = 段错误
    ↑ SEGV_MAPERR = 地址未映射
    ↑ fault addr 0x0 = 空指针！

    x0  0000000000000000  x1  0000007fc8a12340    ← 寄存器
    x2  0000000000000001  x3  0000007f90123456
    ...
    sp  0000007fc8a12300  lr  0000007f80012abc    ← 栈指针、返回地址
    pc  0000007f80012a48                         ← 程序计数器 = Crash 位置

backtrace:                                        ← 调用栈
    #00 pc 00012a48  /data/app/.../lib/arm64/libmylib.so
    #01 pc 00012abc  /data/app/.../lib/arm64/libmylib.so
    #02 pc 00045678  /system/lib64/libart.so
    #03 pc 00089abc  /system/lib64/libart.so
    ↑ #00 和 #01 是你的代码
    ↑ #02 和 #03 是 ART 虚拟机
```

---

## 三、debuggerd 守护进程

### 3.1 工作流程

```
Native Crash 收集流程：

App 进程发生 Crash (信号触发)
    │
    ▼
信号处理器 (sigaction handler)
    │ 由 linker 在进程启动时注册
    ▼
连接 debuggerd (通过 socket)
    │ /dev/socket/crash_dump
    ▼
debuggerd 开始收集信息
    ├── ptrace 附加到崩溃进程
    ├── 读取寄存器状态
    ├── 展开调用栈 (libunwindstack)
    ├── 读取内存映射 (/proc/PID/maps)
    ├── dump 栈内存
    └── 收集其他线程信息
    │
    ▼
写入 Tombstone 文件
    │ /data/tombstones/tombstone_XX
    ▼
通知 AMS (通过 NativeCrashListener)
    │
    ▼
AMS 记录到 DropBox (SYSTEM_TOMBSTONE)
    │
    ▼
进程被终止

源码路径：
system/core/debuggerd/
├── crash_dump.cpp        # Crash dump 主逻辑
├── tombstoned/           # Tombstone 管理守护进程
├── handler/
│   └── debuggerd_handler.cpp  # 信号处理器
└── libdebuggerd/
    ├── tombstone.cpp     # Tombstone 生成
    └── backtrace.cpp     # 调用栈展开
```

---

## 四、ASan / HWASan

### 4.1 地址消毒器概述

```
ASan / HWASan = Address Sanitizer / Hardware Address Sanitizer
              = 内存错误检测工具
              = 编译时插桩 + 运行时检测

能检测的错误类型：
┌────────────────────────────────────────┐
│ • Use-after-free (释放后使用)          │
│ • Heap buffer overflow (堆缓冲区溢出)  │
│ • Stack buffer overflow (栈缓冲区溢出) │
│ • Global buffer overflow (全局变量溢出) │
│ • Use-after-return (返回后使用栈变量)   │
│ • Double-free (重复释放)               │
│ • Memory leak (内存泄漏, LSan)         │
└────────────────────────────────────────┘
```

### 4.2 ASan vs HWASan

```
特性              ASan                HWASan
───────────────────────────────────────────
内存开销         ~2x                 ~1.5x
CPU 开销         ~2x                 ~2x
检测精度         1字节精度            16字节精度 (标签)
硬件要求         无                  ARMv8.5+ (TBI/MTE)
Android 支持     全版本              Android 10+
推荐使用         调试时               CI/测试
```

### 4.3 在 Android 中启用 ASan

```bash
# 方式一：为整个设备启用（需要 AOSP 编译）
# lunch 后选择 _asan 目标
lunch aosp_arm64-userdebug
SANITIZE_TARGET=address make -j32

# 方式二：为单个 App 启用
# 在 Android.bp 中：
cc_library_shared {
    name: "libmylib",
    sanitize: {
        address: true,
    },
}

# 方式三：wrap.sh 方式（不需要重新编译系统）
# 创建 lib/arm64-v8a/wrap.sh:
#!/system/bin/sh
ASAN_OPTIONS=detect_leaks=0 LD_PRELOAD=libclang_rt.asan-aarch64-android.so exec "$@"
```

---

## ✅ 本节自检清单

- [ ] 能说出 SIGSEGV / SIGABRT / SIGBUS 的含义和常见触发场景
- [ ] 能读懂 Tombstone 文件的各个段落
- [ ] 理解 debuggerd 的 Crash 收集流程
- [ ] 了解 ASan / HWASan 的检测能力和使用方式

---

## 💡 思考题

1. 为什么 SIGABRT 通常意味着程序"主动"崩溃？有哪些场景会触发它？
2. Tombstone 中的 fault addr 为 0x0 时几乎可以确定是什么问题？
3. 为什么 HWASan 比 ASan 对内存开销更小？（提示：ARM TBI）
4. debuggerd 如何在不影响崩溃现场的情况下收集信息？（提示：ptrace）
