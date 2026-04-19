# Day 1 上午：Android 系统架构全局观

## 📚 学习目标
- 理解 Android 系统分层架构及各层职责
- 掌握 AOSP 源码核心目录结构
- 理解 Android 系统启动流程
- 了解关键系统进程的角色

---

## 一、Android 系统分层架构

Android 系统采用经典的**分层架构设计**，从下往上依次为：

```
┌─────────────────────────────────────────────────────────────┐
│                      应用层 (Apps)                           │
│         System Apps, Third-party Apps                       │
├─────────────────────────────────────────────────────────────┤
│                   应用框架层 (Framework)                      │
│    Activity Manager, Window Manager, Package Manager...     │
├─────────────────────────────────────────────────────────────┤
│              系统运行库层 (Native Libraries)                   │
│    SurfaceFlinger, Media Framework, OpenGL ES, SQLite...    │
├─────────────────────────────────────────────────────────────┤
│              Android 运行时 (ART)                            │
│    ART / Dalvik 虚拟机, Core Libraries                      │
├─────────────────────────────────────────────────────────────┤
│           硬件抽象层 (HAL - Hardware Abstraction Layer)       │
│    Camera HAL, Audio HAL, Sensors HAL...                    │
├─────────────────────────────────────────────────────────────┤
│              Linux 内核层 (Linux Kernel)                     │
│    进程管理, 内存管理, 驱动程序, Binder IPC...               │
└─────────────────────────────────────────────────────────────┘
```

### 各层职责详解

| 层级 | 核心职责 | 关键组件 |
|------|---------|---------|
| **Linux Kernel** | 硬件抽象、进程/内存管理、驱动、网络 | Binder Driver、Power Management、Display Driver |
| **HAL** | 为 Framework 提供统一硬件接口 | Camera HAL、Audio HAL、GPS HAL |
| **Native Libraries** | C/C++ 系统库，高性能组件 | SurfaceFlinger、OpenGL ES、WebKit |
| **ART** | 应用运行时环境 | DEX 编译、垃圾回收、JIT/AOT |
| **Framework** | 提供应用开发 API | AMS、WMS、PMS、Content Providers |
| **Apps** | 用户应用程序 | System UI、Settings、第三方应用 |

---

## 二、AOSP 源码核心目录结构

```
aosp/
├── build/                  # 构建系统
│   ├── core/              # 核心构建规则
│   └── make/              # Makefile 配置
│
├── frameworks/            # 框架层源码 ⭐核心
│   ├── base/              # 核心框架
│   │   ├── core/          # 核心 API (android.*)
│   │   ├── services/      # 系统服务 (AMS/WMS/PMS)
│   │   └── packages/      # 系统应用 (Settings)
│   └── native/            # Native 框架
│
├── system/                # 系统底层 ⭐核心
│   ├── core/              # 核心系统组件
│   │   ├── init/          # init 进程源码
│   │   ├── logd/          # 日志守护进程
│   │   └── debuggerd/     # Native Crash 处理
│   └── sepolicy/          # SELinux 策略
│
├── hardware/              # HAL 层
│   ├── interfaces/        # HIDL/AIDL 接口定义
│   └── libhardware/       # HAL 库
│
├── kernel/                # Linux 内核
│   └── common/            # 通用内核代码
│
├── bionic/                # Android C 库 (替代 glibc)
│   ├── libc/              # C 标准库
│   ├── libm/              # 数学库
│   └── linker/            # 动态链接器
│
├── art/                   # Android Runtime
│   ├── runtime/           # ART 运行时
│   └── dex2oat/           # DEX 编译器
│
├── packages/              # 应用包
│   ├── apps/              # 系统应用
│   └── providers/         # Content Providers
│
├── device/                # 设备相关配置
│   └── [厂商]/[设备]/     # 具体设备配置
│
└── vendor/                # 厂商私有代码
```

### 🔑 重点关注目录

| 目录 | 内容 | 学习优先级 |
|------|------|-----------|
| `frameworks/base/services` | 系统服务实现 | ⭐⭐⭐ |
| `system/core/init` | init 进程 | ⭐⭐⭐ |
| `frameworks/native/cmds` | Native 命令 | ⭐⭐⭐ |
| `bionic` | C 库实现 | ⭐⭐ |
| `art/runtime` | ART 虚拟机 | ⭐⭐ |

---

## 三、Android 系统启动流程

```
Bootloader
    │
    ▼
┌─────────────┐
│ Linux Kernel │  ← 内核初始化，挂载根文件系统
│  (initramfs) │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  init 进程   │  ← 第一个用户态进程 (PID 1)
│  /system/bin/init │
└──────┬──────┘
       │
       ├── 解析 init.rc 脚本
       ├── 挂载分区 (/system, /data, /vendor)
       ├── 启动关键守护进程
       │
       ▼
┌─────────────┐
│   Zygote    │  ← 应用进程孵化器
│ (app_process)│
└──────┬──────┘
       │
       ├── 预加载常用类/资源
       ├── 创建 Zygote Socket
       │
       ▼
┌─────────────────┐
│  SystemServer   │  ← 系统服务的"大本营"
│ (system_server) │
└──────┬──────────┘
       │
       ├── 启动 AMS (ActivityManagerService)
       ├── 启动 WMS (WindowManagerService)
       ├── 启动 PMS (PackageManagerService)
       ├── 启动其他 80+ 系统服务
       │
       ▼
┌─────────────┐
│  Launcher   │  ← 桌面启动，系统就绪
│  (Home App) │
└─────────────┘
```

### 启动阶段详解

#### 1. Kernel 阶段
```bash
# 内核启动关键日志
[    0.000000] Linux version 5.10.xxx
[    0.001234] Initializing cgroup subsys cpu
[    0.123456] init: init first stage started!
```

#### 2. init 阶段
- **第一阶段**：挂载文件系统，设置权限
- **第二阶段**：解析 `init.rc`，启动服务

```bash
# init.rc 关键配置示例
on boot
    # 设置基本属性
    setprop ro.build.type userdebug
    
    # 启动核心服务
    start logd
    start servicemanager
    start hwservicemanager
    start vndservicemanager

# Zygote 服务定义
service zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server
    class main
    priority -20
    user root
    group root readproc reserved_disk
    socket zygote stream 660 root system
```

#### 3. Zygote 阶段
- 预加载常用 Java 类（约 4000+ 个类）
- 预加载常用资源
- 创建 Socket 监听应用启动请求

#### 4. SystemServer 阶段
```java
// SystemServer.java 核心启动逻辑
private void run() {
    // 1. 创建系统 Context
    createSystemContext();
    
    // 2. 启动 Bootstrap 服务
    startBootstrapServices();
    //    - Installer
    //    - ActivityManagerService
    //    - PowerManagerService
    //    - LightsService
    
    // 3. 启动 Core 服务
    startCoreServices();
    //    - BatteryService
    //    - UsageStatsService
    
    // 4. 启动其他服务
    startOtherServices();
    //    - WindowManagerService
    //    - PackageManagerService
    //    - 70+ 其他服务...
    
    // 5. 进入 Loop
    Looper.loop();
}
```

---

## 四、关键系统进程概览

### 进程关系图

```
init (PID 1)
    │
    ├── servicemanager (PID xxx)  ← Binder 服务注册表
    │
    ├── hwservicemanager          ← HAL 服务管理
    │
    ├── logd                      ← 日志系统守护进程
    │
    ├── surfaceflinger            ← 显示合成服务
    │
    ├── zygote (PID xxx)
    │       │
    │       ├── system_server     ← 系统服务进程
    │       │       ├── AMS 线程
    │       │       ├── WMS 线程
    │       │       └── ...
    │       │
    │       └── com.android.systemui  ← 系统 UI
    │       └── com.android.launcher3 ← 桌面
    │       └── [其他应用进程]
    │
    └── ...
```

### 核心进程详解

| 进程 | 职责 | 源码位置 | 崩溃影响 |
|------|------|---------|---------|
| **init** | 所有进程的祖先，解析 init.rc | `system/core/init/` | 系统无法启动 |
| **servicemanager** | Binder 服务注册表 | `frameworks/native/cmds/servicemanager/` | Binder 通信崩溃 |
| **zygote** | 应用进程孵化器 | `frameworks/base/core/java/com/android/internal/os/ZygoteInit.java` | 无法启动新应用 |
| **system_server** | 运行所有 Java 系统服务 | `frameworks/base/services/java/com/android/server/SystemServer.java` | **系统重启** |
| **surfaceflinger** | 显示合成，送显控制 | `frameworks/native/services/surfaceflinger/` | 黑屏/花屏 |
| **logd** | 日志收集与分发 | `system/core/logd/` | 日志丢失 |

---

## 五、C++ 背景优势发挥点

作为有 C++ 经验的开发者，你在以下领域有明显优势：

### 1. Native 层开发
```cpp
// 示例：Native 系统服务开发
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

int main(int argc, char** argv) {
    // 获取 ServiceManager
    sp<IServiceManager> sm = defaultServiceManager();
    
    // 注册服务
    sm->addService(String16("my_service"), new MyService());
    
    // 启动线程池
    ProcessState::self()->startThreadPool();
    IPCThreadState::self()->joinThreadPool();
    
    return 0;
}
```

### 2. Native Crash 分析
- 理解信号机制 (SIGSEGV, SIGABRT)
- 熟悉内存映射、调用栈解析
- 能阅读 Tombstone 文件

### 3. 性能优化
- 使用 simpleperf 进行 CPU Profiling
- Native 内存泄漏检测
- 系统级性能调优

---

## ✅ 本节自检清单

- [ ] 能画出 Android 分层架构图
- [ ] 能说出 AOSP 核心目录的作用
- [ ] 能描述从开机到 Launcher 的完整启动流程
- [ ] 知道 system_server 崩溃的后果
- [ ] 理解 init、Zygote、SystemServer 的关系

---

## 📖 延伸阅读

1. **《深入理解 Android 内核设计思想》** - 林学森
2. **Gityuan 博客** - [Android 系统启动流程](http://gityuan.com/android/)
3. **AOSP 官方文档** - https://source.android.com/docs/core/architecture

---

## 💡 思考题

1. 为什么 Android 要用 Zygote 机制来启动应用，而不是直接 fork？
2. system_server 为什么如此重要？它崩溃后系统会如何恢复？
3. Binder 驱动在内核层，它与其他 IPC 机制（如管道、共享内存）相比有什么优势？
