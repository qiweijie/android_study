# Day 3 上午：SystemServer & 核心系统服务

## 📚 学习目标
- 理解 SystemServer 的启动流程和三阶段服务加载
- 掌握 AMS 的核心职责：进程管理、四大组件、OOM Adj
- 了解 PMS 的 APK 安装/扫描与权限管理机制
- 理解 WMS 的窗口管理与 ANR 判定角色
- 建立对 system_server 稳定性重要性的认知

---

## 一、SystemServer 概述

### 1.1 为什么 SystemServer 是「心脏」

```
Android 系统进程层次：

  init (PID: 1)
    │
    ├── Zygote (PID: ~300)
    │     │
    │     ├── system_server (PID: ~500)      ← 本章主角
    │     │     ├── ActivityManagerService
    │     │     ├── WindowManagerService
    │     │     ├── PackageManagerService
    │     │     ├── PowerManagerService
    │     │     ├── ... (80+ 系统服务)
    │     │     └── Watchdog（守护线程）
    │     │
    │     ├── App A (PID: 1000)
    │     ├── App B (PID: 1001)
    │     └── App C (PID: 1002)
    │
    ├── surfaceflinger
    ├── servicemanager
    └── logd
```

**关键认知**：
- `system_server` 承载了几乎所有核心 Java 系统服务
- 它崩溃 = Zygote 重启 = 所有 App 重启 = 用户感知「系统重启」
- 稳定性建设中，**system_server 崩溃是 P0 级别事故**

### 1.2 system_server 的诞生

```
启动链路：

init 进程
  │ 解析 init.rc
  ▼
启动 Zygote 进程
  │ app_process → ZygoteInit.main()
  ▼
Zygote 预加载
  │ 加载常用类、资源、共享库
  ▼
Zygote fork 出 system_server     ← 关键步骤
  │ ZygoteInit.forkSystemServer()
  ▼
SystemServer.main()
  │
  ▼
开始注册系统服务...
```

源码路径：
```
frameworks/base/services/java/com/android/server/SystemServer.java
```

---

## 二、SystemServer 三阶段启动

### 2.1 整体流程

```java
// SystemServer.java 核心逻辑
public static void main(String[] args) {
    new SystemServer().run();
}

private void run() {
    // 准备工作
    Looper.prepareMainLooper();
    createSystemContext();
    
    // 创建 SystemServiceManager
    mSystemServiceManager = new SystemServiceManager(mSystemContext);

    // ===== 三阶段启动 =====
    startBootstrapServices();   // 第一阶段：引导服务
    startCoreServices();        // 第二阶段：核心服务
    startOtherServices();       // 第三阶段：其他服务

    // 进入消息循环
    Looper.loop();
}
```

### 2.2 第一阶段：引导服务 (Bootstrap Services)

这些服务是最基础的，其他服务依赖它们才能启动。

```
startBootstrapServices() 启动的关键服务：

┌─────────────────────────────────────────────────────┐
│               Bootstrap Services                     │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │   Installer      │  │ DeviceIdentifiers│        │
│  │   安装器服务      │  │   设备标识服务    │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │      AMS ⭐      │  │    PowerMS       │        │
│  │ ActivityManager  │  │   电源管理服务    │        │
│  │ 进程 & 组件管理   │  │                  │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │      PMS ⭐      │  │  DisplayManager  │        │
│  │ PackageManager   │  │   显示管理服务    │        │
│  │ 包管理 & 权限     │  │                  │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐                              │
│  │  SensorService   │                              │
│  │   传感器服务      │                              │
│  └──────────────────┘                              │
│                                                     │
│  启动顺序有严格依赖：Installer → AMS → PMS → ...    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**为什么 AMS 和 PMS 必须最先启动？**
- AMS 管理所有进程和组件，其他服务注册时需要它
- PMS 管理所有包信息和权限，AMS 启动组件时需要查包

### 2.3 第二阶段：核心服务 (Core Services)

```
startCoreServices() 启动的服务：

┌─────────────────────────────────────────┐
│            Core Services                │
├─────────────────────────────────────────┤
│                                         │
│  ┌──────────────────┐                  │
│  │   BatteryService │  电池管理         │
│  └──────────────────┘                  │
│  ┌──────────────────┐                  │
│  │   UsageStatsService│ 应用使用统计    │
│  └──────────────────┘                  │
│  ┌──────────────────┐                  │
│  │   WebViewUpdate  │  WebView 更新     │
│  └──────────────────┘                  │
│  ┌──────────────────┐                  │
│  │   BugreportManager│ Bugreport 管理  │
│  └──────────────────┘                  │
│                                         │
└─────────────────────────────────────────┘
```

### 2.4 第三阶段：其他服务 (Other Services)

数量最多，包含 WMS、InputManager 等重要服务。

```
startOtherServices() 启动的关键服务：

┌─────────────────────────────────────────────────────┐
│              Other Services（部分列举）               │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │      WMS ⭐      │  │   InputManager   │        │
│  │ WindowManager    │  │   输入事件管理    │        │
│  │ 窗口 & ANR 判定   │  │                  │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │   NetworkPolicy  │  │   ConnectivityMS │        │
│  │   网络策略管理    │  │   网络连接管理    │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │  AlarmManager    │  │   NotificationMS │        │
│  │  闹钟/定时管理    │  │   通知管理服务    │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  ┌──────────────────┐  ┌──────────────────┐        │
│  │    Watchdog ⭐   │  │  DropBoxManager  │        │
│  │   看门狗（自检）  │  │   异常事件存储    │        │
│  └──────────────────┘  └──────────────────┘        │
│                                                     │
│  最后调用 AMS.systemReady() → 启动 Launcher         │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 2.5 服务注册到 ServiceManager

所有系统服务最终都通过 Binder 注册到 ServiceManager（Day 2 学过）：

```java
// 以 AMS 为例
ServiceManager.addService(Context.ACTIVITY_SERVICE, mService);
// 等价于：
ServiceManager.addService("activity", ams);

// 之后任何进程都可以通过 Binder 访问：
IBinder binder = ServiceManager.getService("activity");
IActivityManager am = IActivityManager.Stub.asInterface(binder);
```

---

## 三、AMS — ActivityManagerService

### 3.1 核心职责

AMS 是 Android 中最重要、也是最复杂的系统服务。

```
AMS 的四大核心职责：

┌─────────────────────────────────────────────────────┐
│              ActivityManagerService                   │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1️⃣ 四大组件生命周期管理                              │
│  ┌─────────────────────────────────────────┐       │
│  │ Activity: 启动/暂停/恢复/销毁            │       │
│  │ Service:  启动/绑定/解绑/停止            │       │
│  │ BroadcastReceiver: 注册/发送/调度        │       │
│  │ ContentProvider: 安装/查询/发布          │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  2️⃣ 进程管理                                        │
│  ┌─────────────────────────────────────────┐       │
│  │ 进程创建: 通过 Zygote fork 新进程        │       │
│  │ 进程回收: 根据 OOM Adj 等级决定回收       │       │
│  │ 进程优先级: 动态调整 adj 值              │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  3️⃣ ANR 监控                                        │
│  ┌─────────────────────────────────────────┐       │
│  │ Input ANR: 5 秒无响应                    │       │
│  │ BroadcastReceiver ANR: 前台 10s / 后台 60s│       │
│  │ Service ANR: 前台 20s / 后台 200s        │       │
│  │ ContentProvider ANR: 10 秒               │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  4️⃣ Task & Activity 栈管理                           │
│  ┌─────────────────────────────────────────┐       │
│  │ Activity 栈: Back Stack 管理             │       │
│  │ Task: 任务栈、启动模式                   │       │
│  │ 最近任务: Recent Tasks 列表              │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 3.2 OOM Adj 机制

OOM Adj 是 Android 内存管理的核心——决定了「内存不足时先杀谁」。

```
OOM Adj 等级（从高优先级到低优先级）：

等级                    adj 值    说明
────────────────────────────────────────────
NATIVE_ADJ             -1000    Native 进程（不被管理）
SYSTEM_ADJ              -900    system_server（永不杀）
PERSISTENT_PROC_ADJ     -800    常驻进程（如电话）
PERSISTENT_SERVICE_ADJ  -700    常驻服务绑定的进程

FOREGROUND_APP_ADJ         0    前台应用 ← 用户正在看
VISIBLE_APP_ADJ          100    可见但非前台
PERCEPTIBLE_APP_ADJ      200    用户可感知（如播放音乐）
PERCEPTIBLE_LOW_APP_ADJ  250    低感知

BACKUP_APP_ADJ           300    正在备份
HEAVY_WEIGHT_APP_ADJ     400    重量级后台
SERVICE_ADJ              500    有 Service 运行
HOME_APP_ADJ             600    桌面 Launcher
PREVIOUS_APP_ADJ         700    上一个应用

SERVICE_B_ADJ            800    老旧 Service
CACHED_APP_MIN_ADJ       900    缓存进程（最先被杀）
CACHED_APP_MAX_ADJ       999    缓存进程（最先被杀）
────────────────────────────────────────────

内存不足时的回收顺序：
adj=999 → 998 → ... → 900 → 800 → 700 → ...
从最不重要的缓存进程开始杀起
```

### 3.3 AMS 中的进程创建流程

```
启动一个 App 的完整流程：

用户点击图标
    │
    ▼
Launcher → AMS.startActivity()
    │
    ▼
AMS 检查目标进程是否存在
    │
    ├── 存在 → 直接调度 Activity 生命周期
    │
    └── 不存在 ↓
        │
        ▼
    AMS → Zygote.fork()  (通过 Socket 通信)
        │
        ▼
    新进程启动 → ActivityThread.main()
        │
        ▼
    新进程 → AMS.attachApplication()  (Binder 回调)
        │
        ▼
    AMS → 调度 Activity.onCreate()
        │
        ▼
    应用界面显示
```

### 3.4 AMS 关键源码路径

```
frameworks/base/services/core/java/com/android/server/am/
├── ActivityManagerService.java      # AMS 主类（巨大，2万+ 行）
├── ProcessList.java                 # 进程列表管理
├── OomAdjuster.java                 # OOM Adj 计算
├── BroadcastQueue.java              # 广播队列
├── ActiveServices.java              # Service 管理
├── ContentProviderHelper.java       # ContentProvider 管理
└── AppErrors.java                   # 应用错误处理（Crash/ANR）
```

---

## 四、PMS — PackageManagerService

### 4.1 核心职责

```
PMS 的三大核心职责：

┌─────────────────────────────────────────────────────┐
│             PackageManagerService                     │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1️⃣ APK 扫描与解析                                   │
│  ┌─────────────────────────────────────────┐       │
│  │ 开机扫描: /system/app、/data/app 下 APK  │       │
│  │ 解析 AndroidManifest.xml                 │       │
│  │ 提取四大组件、权限声明、Intent Filter     │       │
│  │ 构建内存中的包信息数据库                  │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  2️⃣ APK 安装/卸载/更新                                │
│  ┌─────────────────────────────────────────┐       │
│  │ 安装: 校验签名 → 拷贝 APK → 解析 → dexopt│       │
│  │ 卸载: 清理 APK、数据、缓存               │       │
│  │ 更新: 签名校验 → 版本比较 → 替换         │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  3️⃣ 权限管理                                        │
│  ┌─────────────────────────────────────────┐       │
│  │ 权限声明: <uses-permission>             │       │
│  │ 权限授予: 安装时/运行时权限              │       │
│  │ 权限检查: checkPermission()             │       │
│  │ 权限组管理: 危险权限分组                  │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 4.2 APK 扫描流程

```
开机时 PMS 的扫描流程：

PMS 构造函数
    │
    ▼
扫描系统目录
    ├── /system/framework/    ← 框架 JAR
    ├── /system/app/          ← 系统预装 App
    ├── /system/priv-app/     ← 特权系统 App
    ├── /vendor/app/          ← 厂商预装 App
    └── /data/app/            ← 用户安装的 App
    │
    ▼
对每个 APK：
    ├── 解析 AndroidManifest.xml
    ├── 提取包名、版本、签名
    ├── 记录四大组件、Intent Filter
    ├── 处理权限声明
    └── 写入 packages.xml（持久化）
    │
    ▼
构建完成 → 内存中的包信息数据库可用
    │
    ▼
其他服务（如 AMS）可以查询包信息
```

### 4.3 PMS 与稳定性的关系

| 场景 | 影响 | 表现 |
|------|------|------|
| 开机扫描慢 | 启动时间长 | 用户等待开机时间过长 |
| packages.xml 损坏 | 包信息丢失 | 应用无法启动 |
| dexopt 失败 | 应用无法运行 | ClassNotFoundException |
| 权限数据异常 | 安全风险 | 权限检查绕过 |

### 4.4 关键源码路径

```
frameworks/base/services/core/java/com/android/server/pm/
├── PackageManagerService.java      # PMS 主类
├── Settings.java                   # packages.xml 读写
├── PackageParser.java              # APK 解析器
├── PackageDexOptimizer.java        # dex 优化
├── InstallPackageHelper.java       # 安装逻辑
└── permission/
    └── PermissionManagerService.java  # 权限管理
```

---

## 五、WMS — WindowManagerService

### 5.1 核心职责

```
WMS 的三大核心职责：

┌─────────────────────────────────────────────────────┐
│             WindowManagerService                     │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1️⃣ 窗口管理                                        │
│  ┌─────────────────────────────────────────┐       │
│  │ 窗口层级: Z-order 排列所有窗口           │       │
│  │ 窗口类型: Application / System / Toast   │       │
│  │ 窗口添加/移除: addWindow / removeWindow  │       │
│  │ 窗口布局: 计算窗口大小和位置             │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  2️⃣ 焦点管理（与 ANR 判定密切相关）                    │
│  ┌─────────────────────────────────────────┐       │
│  │ 输入焦点: 哪个窗口接收键盘/触摸事件      │       │
│  │ 焦点切换: Activity 切换时转移焦点        │       │
│  │ ANR 判定: 焦点窗口 5 秒未响应输入事件     │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
│  3️⃣ 转场动画                                        │
│  ┌─────────────────────────────────────────┐       │
│  │ Activity 切换动画                        │       │
│  │ 窗口打开/关闭动画                        │       │
│  │ 壁纸切换动画                             │       │
│  └─────────────────────────────────────────┘       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 5.2 窗口层级模型

```
Android 窗口层级（Z-order 从上到下）：

层级                    窗口类型          示例
─────────────────────────────────────────────
最上层（优先显示）
  │    TYPE_SYSTEM_ERROR       系统错误对话框
  │    TYPE_SYSTEM_OVERLAY     系统浮窗
  │    TYPE_STATUS_BAR         状态栏
  │    TYPE_NAVIGATION_BAR     导航栏
  │    TYPE_TOAST              Toast 提示
  │    TYPE_INPUT_METHOD       输入法窗口
  │    TYPE_SYSTEM_DIALOG      系统对话框
  │    TYPE_APPLICATION        应用窗口（Activity）
  │    TYPE_WALLPAPER          壁纸窗口
  ▼
最底层
```

### 5.3 WMS 与 ANR 判定

WMS 在 ANR 判定中扮演关键角色——它知道「哪个窗口应该接收输入事件」。

```
Input ANR 判定流程：

InputDispatcher（Native 层）
    │
    │ 1. 收到输入事件（触摸/按键）
    ▼
向 WMS 查询焦点窗口
    │
    │ 2. WMS 返回当前焦点窗口信息
    ▼
将事件派发给焦点窗口
    │
    │ 3. 等待窗口处理完成
    │
    ├── 5 秒内处理完成 → 正常
    │
    └── 超过 5 秒未处理 → ANR!
        │
        ▼
    InputDispatcher 通知 AMS
        │
        ▼
    AMS 收集信息 → 弹出 ANR 对话框
        │ 同时写入 /data/anr/traces.txt
```

**稳定性关键点**：
- Input ANR 的 5 秒计时是在 **InputDispatcher (Native C++)** 中实现的
- WMS 负责维护焦点窗口信息
- AMS 负责最终的 ANR 处理（弹框、写日志）
- 三者协作完成 ANR 检测

### 5.4 关键源码路径

```
frameworks/base/services/core/java/com/android/server/wm/
├── WindowManagerService.java        # WMS 主类
├── WindowState.java                 # 窗口状态
├── DisplayContent.java              # 显示内容管理
├── WindowToken.java                 # 窗口令牌
├── ActivityRecord.java              # Activity 记录
├── Task.java                        # 任务栈
└── InputMonitor.java                # 输入监控（ANR 相关）

# ANR 相关的 Native 代码
frameworks/native/services/inputflinger/
├── dispatcher/InputDispatcher.cpp   # 输入事件分发 & ANR 计时
└── dispatcher/InputDispatcher.h
```

---

## 六、三大服务的协作关系

### 6.1 启动一个 Activity 的协作

```
用户点击 App 图标 → 三大服务协同工作：

Step 1: AMS 处理启动请求
┌────────────────────────────┐
│ AMS.startActivity()        │
│  ├── 检查权限（调 PMS）     │
│  ├── 查找目标 Activity      │
│  ├── 必要时 fork 新进程     │
│  └── 调度 Activity 生命周期 │
└────────────┬───────────────┘
             │
Step 2: PMS 提供包信息
┌────────────┴───────────────┐
│ PMS.resolveIntent()        │
│  ├── 查找匹配的 Activity   │
│  ├── 检查权限声明           │
│  └── 返回 ResolveInfo      │
└────────────┬───────────────┘
             │
Step 3: WMS 管理窗口
┌────────────┴───────────────┐
│ WMS.addWindow()            │
│  ├── 创建窗口              │
│  ├── 计算布局              │
│  ├── 设置窗口焦点          │
│  └── 播放转场动画          │
└────────────────────────────┘
             │
             ▼
        用户看到 App 界面
```

### 6.2 稳定性视角下的协作问题

| 场景 | 涉及服务 | 后果 |
|------|---------|------|
| AMS 死锁 | AMS | 所有 Activity 无法启动/切换 |
| PMS 扫描卡住 | PMS → AMS | 开机慢，应用信息不可用 |
| WMS 焦点异常 | WMS → AMS | 输入事件丢失，误报 ANR |
| AMS + WMS 互锁 | AMS ↔ WMS | system_server 卡死 → Watchdog 触发 |

### 6.3 为什么 AMS 与 WMS 容易死锁

```
经典的 AMS-WMS 死锁场景：

Thread A (AMS):                  Thread B (WMS):
  synchronized(AMS.lock) {         synchronized(WMS.lock) {
    // 持有 AMS 锁                    // 持有 WMS 锁
    // 需要调用 WMS                    // 需要调用 AMS
    WMS.doSomething();  ← 等待        AMS.doSomething();  ← 等待
  }                                }

结果：互相等待 → 死锁 → Watchdog 检测到 → 杀掉 system_server
```

这是 Android 系统稳定性中最经典的问题之一。Android 团队在历代版本中做了大量重构来减少锁竞争。

---

## 七、Watchdog 机制（预览）

### 7.1 基本原理

Watchdog 是 system_server 的「自检守护线程」。

```
Watchdog 工作原理：

┌─────────────────────────────────────────────────────┐
│              Watchdog Thread                          │
│                                                     │
│  每 30 秒检查一次：                                   │
│                                                     │
│  ┌─────────────────────┐                           │
│  │ 向各关键线程的 Handler │                          │
│  │ 投递一个检查消息      │                           │
│  └──────────┬──────────┘                           │
│             │                                       │
│             ▼                                       │
│  ┌─────────────────────┐                           │
│  │ 等待 30 秒           │                           │
│  └──────────┬──────────┘                           │
│             │                                       │
│             ▼                                       │
│  检查消息是否已被处理：                                │
│  ├── 已处理 → 正常，继续下一轮                        │
│  └── 未处理 → 该线程可能卡住了！                       │
│       │                                             │
│       ▼                                             │
│  ┌─────────────────────┐                           │
│  │ 再等 30 秒（总共 60s）│                           │
│  └──────────┬──────────┘                           │
│             │                                       │
│             ▼                                       │
│  仍未处理 → WATCHDOG KILLING SYSTEM PROCESS          │
│  dump 线程堆栈 → 杀死 system_server                  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 7.2 Watchdog 监控的关键线程

```java
// Watchdog.java 中注册的 HandlerChecker
// 这些是 system_server 中最关键的线程：

"main thread"               // 主线程（消息循环）
"android.fg"                // 前台线程
"android.ui"                // UI 线程
"android.io"                // IO 线程
"android.display"           // 显示线程
"ActivityManager"           // AMS 工作线程
"WindowManager"             // WMS 工作线程
"PowerManagerService"       // 电源管理线程
```

任何一个被监控的线程卡住超过 60 秒，Watchdog 就会杀掉整个 system_server。

### 7.3 Watchdog 关键源码路径

```
frameworks/base/services/core/java/com/android/server/Watchdog.java
```

> 下午实操会详细阅读这个类的源码。

---

## ✅ 本节自检清单

- [ ] 能说出 SystemServer 三阶段启动包含哪些关键服务
- [ ] 理解 AMS 的四大核心职责
- [ ] 能解释 OOM Adj 机制和进程回收顺序
- [ ] 理解 PMS 的 APK 扫描与权限管理流程
- [ ] 理解 WMS 的窗口管理与焦点控制
- [ ] 知道 WMS 在 ANR 判定中的角色
- [ ] 理解 AMS/WMS/PMS 三者的协作关系
- [ ] 了解 Watchdog 的基本工作原理

---

## 📝 学习笔记要点

建议记录：
1. SystemServer 三阶段各有哪些关键服务，启动顺序的依赖关系
2. OOM Adj 各等级的含义和回收顺序
3. Input ANR 的判定涉及 InputDispatcher(Native) + WMS + AMS 三者协作
4. AMS 与 WMS 死锁是稳定性高频问题
5. Watchdog 的检查周期和超时阈值

---

## 💡 思考题

1. 为什么 AMS 和 PMS 必须在 Bootstrap 阶段最先启动？如果它们启动失败会怎样？
2. 一个 adj=900 的缓存进程和 adj=0 的前台进程，内存不足时谁先被杀？
3. 如果 WMS 的焦点窗口计算出错，会导致什么稳定性问题？
4. Watchdog 为什么设置 60 秒的超时而不是更短（如 10 秒）？
5. AMS 与 WMS 死锁时，能否通过代码设计完全避免？Android 是如何缓解的？
