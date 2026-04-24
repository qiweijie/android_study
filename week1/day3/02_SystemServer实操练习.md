# Day 3 下午：SystemServer 实操练习

## 🎯 实操目标
- 阅读 SystemServer.java 源码，定位三阶段启动方法
- 用 dumpsys 深入查看 AMS / WMS / PMS 的运行状态
- 阅读 Watchdog.java 源码，理解 system_server 自检原理
- 模拟 system_server 卡顿，观察 Watchdog 触发行为

---

## 实验一：阅读 SystemServer.java 源码

### 1.1 源码位置

```
frameworks/base/services/java/com/android/server/SystemServer.java
```

### 1.2 关键方法定位

打开 SystemServer.java 后，搜索以下方法：

```java
// 入口
public static void main(String[] args) {
    new SystemServer().run();
}

// 核心 run() 方法 —— 从这里开始阅读
private void run() {
    try {
        // ... 系统属性设置、时区初始化等准备工作

        // 创建主 Looper
        Looper.prepareMainLooper();

        // 加载 native 库
        System.loadLibrary("android_servers");

        // 创建系统上下文
        createSystemContext();

        // 创建 SystemServiceManager（服务管理器）
        mSystemServiceManager = new SystemServiceManager(mSystemContext);

        // ===== 三阶段启动 =====
        startBootstrapServices();   // 搜索这个方法
        startCoreServices();        // 搜索这个方法
        startOtherServices();       // 搜索这个方法

    } catch (Throwable ex) {
        throw ex;
    } finally {
        // ... 性能 trace 结束
    }

    // 进入无限消息循环
    Looper.loop();
    throw new RuntimeException("Main thread loop unexpectedly exited");
}
```

### 1.3 阅读指引：startBootstrapServices()

```java
private void startBootstrapServices() {
    // 关注以下服务的启动顺序和依赖关系：

    // 1. Installer — 需要最先启动，后续安装操作依赖它
    Installer installer = mSystemServiceManager.startService(Installer.class);

    // 2. AMS — 最核心的服务
    ActivityTaskManagerService atm = mSystemServiceManager.startService(
            ActivityTaskManagerService.Lifecycle.class).getService();
    mActivityManagerService = ActivityManagerService.Lifecycle.startService(
            mSystemServiceManager, atm);

    // 3. PowerManagerService — 电源管理
    mPowerManagerService = mSystemServiceManager.startService(
            PowerManagerService.class);

    // 4. PackageManagerService — 包管理
    //    注意：这里会触发 APK 扫描，是开机最耗时的阶段之一
    mPackageManagerService = PackageManagerService.main(
            mSystemContext, installer, ...);

    // ... 其他 Bootstrap 服务
}
```

### 1.4 阅读指引：startOtherServices()

```java
private void startOtherServices() {
    // 这个方法非常长（数千行），关注关键服务：

    // 1. WindowManagerService
    wm = WindowManagerService.main(context, inputManager, ...);
    ServiceManager.addService(Context.WINDOW_SERVICE, wm);

    // 2. InputManagerService
    inputManager = new InputManagerService(context);

    // 3. AlarmManagerService
    mSystemServiceManager.startService(AlarmManagerService.class);

    // 4. Watchdog 初始化
    final Watchdog watchdog = Watchdog.getInstance();
    watchdog.init(context, mActivityManagerService);

    // ... 最后，通知 AMS 系统启动完成
    mActivityManagerService.systemReady(() -> {
        // 系统就绪回调
        // 这里会启动 Launcher
    });
}
```

### 1.5 练习：梳理启动时序

阅读源码后，填写以下启动时序表：

```
序号    服务名称                    所属阶段        依赖关系
──────────────────────────────────────────────────────
 1     Installer                   Bootstrap      无
 2     ActivityManagerService      Bootstrap      依赖 Installer
 3     PowerManagerService         Bootstrap      依赖 AMS
 4     PackageManagerService       Bootstrap      依赖 Installer
 5     ____________________        ________       ____________
 6     ____________________        ________       ____________
 7     WindowManagerService        Other          依赖 AMS、InputMS
 8     Watchdog                    Other          监控所有关键线程
 ...
```

---

## 实验二：用 dumpsys 深入各服务

### 2.1 dumpsys 基础

`dumpsys` 是 Android 最强大的调试命令之一，它可以输出任意系统服务的内部状态。

```bash
# 列出所有支持 dumpsys 的服务
adb shell dumpsys -l

# 基本语法
adb shell dumpsys <服务名> [选项]
```

### 2.2 AMS 深入 — dumpsys activity

#### 查看所有 Activity 栈

```bash
# 查看当前 Activity 栈
adb shell dumpsys activity activities

# 输出关键字段解读：
# ACTIVITY MANAGER ACTIVITIES
#   Display #0:
#     Task #123
#       * ActivityRecord{xxx com.example.app/.MainActivity}
#         State: RESUMED         ← Activity 状态
#         app=ProcessRecord{xxx pid=12345}
```

#### 查看进程信息

```bash
# 查看所有进程及其 OOM Adj 值
adb shell dumpsys activity processes

# 关注输出中的：
# ACTIVITY MANAGER RUNNING PROCESSES
#   Process com.example.app (pid 12345)
#     oom adj: 0                ← 前台应用
#     state: TOP                ← 进程状态
#     curAdj=0 setAdj=0        ← 当前/设置 adj 值
```

#### 查看 OOM Adj 等级

```bash
# 精简版：只看进程和 adj
adb shell dumpsys activity oom

# 输出示例：
# ACTIVITY MANAGER PROCESS OOM ADJ
#   Proc # 0: fore  F/A/T  trm: 0   12345:com.example.app/u0a123 (top-activity)
#   Proc # 1: vis   F/A/S  trm: 0   12346:com.android.systemui/u0a100 (visible)
#   Proc # 2: prcp  B/ /S  trm: 0   12347:com.android.phone/1000 (service)
#   ...
#   Proc #10: cch+5 B/ /CE trm: 0   12400:com.example.cached/u0a200 (cached)
```

#### 查看广播队列

```bash
# 查看当前广播状态
adb shell dumpsys activity broadcasts

# 关注：
# - 是否有广播堆积（可能导致 ANR）
# - 各广播的处理耗时
```

#### 查看服务状态

```bash
# 查看所有运行中的 Service
adb shell dumpsys activity services

# 查看特定包的 Service
adb shell dumpsys activity services com.example.app
```

### 2.3 WMS 深入 — dumpsys window

#### 查看窗口列表

```bash
# 查看所有窗口
adb shell dumpsys window windows

# 输出关键字段：
# Window #0 Window{xxx StatusBar}
#   mOwnerUid=10042 mShowToOwnerOnly=false
#   isVisible=true
#   mLayer=231000
#
# Window #1 Window{xxx com.example.app/MainActivity}
#   mOwnerUid=10123
#   mFocusable=true           ← 是否可获取焦点
#   isVisible=true
```

#### 查看焦点窗口（ANR 相关）

```bash
# 查看当前焦点窗口
adb shell dumpsys window displays | grep -E "mCurrentFocus|mFocusedApp"

# 输出示例：
# mCurrentFocus=Window{xxx com.example.app/MainActivity}
# mFocusedApp=ActivityRecord{xxx com.example.app/.MainActivity}
```

#### 查看输入相关信息

```bash
# 查看输入焦点和 ANR 超时
adb shell dumpsys window input

# 关注 InputDispatcher 的状态
```

#### 查看窗口策略

```bash
# 查看窗口策略配置
adb shell dumpsys window policy

# 关注：状态栏、导航栏、屏幕旋转等策略
```

### 2.4 PMS 深入 — dumpsys package

#### 查看包信息

```bash
# 查看特定包的完整信息
adb shell dumpsys package com.example.app

# 输出关键字段：
# Package [com.example.app]
#   versionCode=1
#   versionName=1.0
#   applicationInfo:
#     targetSdk=35
#     flags=[ HAS_CODE ALLOW_BACKUP ]
#   permissions:
#     android.permission.INTERNET: granted=true
#   declared permissions: (none)
```

#### 查看权限信息

```bash
# 查看某个应用的权限状态
adb shell dumpsys package com.example.app | grep -A 2 "permission"

# 查看所有危险权限的授予状态
adb shell dumpsys package permissions
```

#### 查看 Intent 解析

```bash
# 查看 Intent Filter 注册情况
adb shell dumpsys package resolvers

# 查看特定 Activity 的解析
adb shell dumpsys package intent-filter-verifiers
```

#### 查看安装信息

```bash
# 查看所有已安装包的简要信息
adb shell dumpsys package packages | grep -E "Package \[|versionCode|codePath"
```

### 2.5 综合练习

在设备上打开一个应用，然后完成以下任务：

```bash
# 练习 1：找到该应用的进程 PID 和 OOM Adj
adb shell dumpsys activity oom | grep <包名>

# 练习 2：找到该应用的 Activity 栈状态
adb shell dumpsys activity activities | grep -A 5 <包名>

# 练习 3：确认该应用的窗口是否有焦点
adb shell dumpsys window displays | grep mCurrentFocus

# 练习 4：查看该应用声明了哪些权限
adb shell dumpsys package <包名> | grep "permission"

# 练习 5：将应用切到后台，再次查看 OOM Adj 变化
# 对比前台和后台的 adj 值差异
```

---

## 实验三：追踪 Watchdog 机制

### 3.1 源码位置

```
frameworks/base/services/core/java/com/android/server/Watchdog.java
```

### 3.2 核心源码阅读

#### HandlerChecker — 线程检查器

```java
// Watchdog.java 内部类
public final class HandlerChecker implements Runnable {
    private final Handler mHandler;       // 被监控线程的 Handler
    private final String mName;           // 线程名称
    private final long mWaitMax;          // 最大等待时间
    private boolean mCompleted;           // 检查是否完成
    private long mStartTime;             // 开始检查的时间

    @Override
    public void run() {
        // 这个 Runnable 被投递到被监控线程中执行
        // 如果线程正常运行，它会被及时执行
        // 如果线程卡住了，它就不会执行
        mCompleted = true;  // 标记为已完成
    }

    public void scheduleCheckLocked() {
        mCompleted = false;  // 重置标记
        mStartTime = SystemClock.uptimeMillis();
        // 向被监控线程的 Handler 投递消息
        mHandler.postAtFrontOfQueue(this);
    }

    public int getCompletionStateLocked() {
        if (mCompleted) {
            return COMPLETED;        // 正常
        }
        long elapsed = SystemClock.uptimeMillis() - mStartTime;
        if (elapsed < mWaitMax / 2) {
            return WAITING;          // 还在等
        } else if (elapsed < mWaitMax) {
            return WAITED_HALF;      // 等了一半（30s）
        }
        return OVERDUE;              // 超时（60s）→ 要杀进程了
    }
}
```

#### Watchdog 主循环 — run() 方法

```java
// Watchdog.java
@Override
public void run() {
    boolean waitedHalf = false;

    while (true) {
        // ====== Step 1: 向所有监控线程投递检查消息 ======
        synchronized (mLock) {
            for (int i = 0; i < mHandlerCheckers.size(); i++) {
                HandlerChecker hc = mHandlerCheckers.get(i);
                hc.scheduleCheckLocked();
            }
        }

        // ====== Step 2: 等待 30 秒 ======
        long start = SystemClock.uptimeMillis();
        while (timeout > 0) {
            try {
                mLock.wait(timeout);
            } catch (InterruptedException e) { }
            timeout = CHECK_INTERVAL - (SystemClock.uptimeMillis() - start);
        }

        // ====== Step 3: 检查各线程状态 ======
        final int waitState = evaluateCheckerCompletionLocked();

        if (waitState == COMPLETED) {
            // 所有线程正常，继续下一轮
            waitedHalf = false;
            continue;
        } else if (waitState == WAITING) {
            continue;
        } else if (waitState == WAITED_HALF) {
            if (!waitedHalf) {
                // 第一次超过 30s，先 dump 堆栈作为预警
                // 但还不杀进程
                waitedHalf = true;
            }
            continue;
        }

        // ====== Step 4: 超时 60s —— 杀掉 system_server ======
        // waitState == OVERDUE

        // 1. 收集堆栈信息
        final ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Process.myPid());
        // 同时 dump native 进程堆栈

        // 2. 写入 traces 文件
        File stack = ActivityManagerService.dumpStackTraces(pids, ...);

        // 3. 输出日志
        Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + subject);

        // 4. 写入 DropBox（持久化）
        if (mActivity != null) {
            mActivity.addErrorToDropBox("watchdog", ...);
        }

        // 5. 杀死自己
        Slog.w(TAG, "*** GOODBYE!");
        Process.killProcess(Process.myPid());
        System.exit(10);
    }
}
```

### 3.3 Watchdog 注册的监控线程

```java
// Watchdog 构造函数中注册的 HandlerChecker
private Watchdog() {
    // 前台线程 Handler
    mHandlerCheckers.add(new HandlerChecker(
        FgThread.getHandler(), "foreground thread", DEFAULT_TIMEOUT));

    // 主线程 Handler
    mHandlerCheckers.add(new HandlerChecker(
        new Handler(Looper.getMainLooper()), "main thread", DEFAULT_TIMEOUT));

    // UI 线程
    mHandlerCheckers.add(new HandlerChecker(
        UiThread.getHandler(), "ui thread", DEFAULT_TIMEOUT));

    // IO 线程
    mHandlerCheckers.add(new HandlerChecker(
        IoThread.getHandler(), "i/o thread", DEFAULT_TIMEOUT));

    // Display 线程
    mHandlerCheckers.add(new HandlerChecker(
        DisplayThread.getHandler(), "display thread", DEFAULT_TIMEOUT));

    // Animation 线程
    mHandlerCheckers.add(new HandlerChecker(
        AnimationThread.getHandler(), "animation thread", DEFAULT_TIMEOUT));

    // Surface Animation 线程
    mHandlerCheckers.add(new HandlerChecker(
        SurfaceAnimationThread.getHandler(),
        "surface animation thread", DEFAULT_TIMEOUT));

    // DEFAULT_TIMEOUT = 60 * 1000 (60秒)
}
```

### 3.4 Watchdog 状态机总结

```
Watchdog 状态机：

                    投递检查消息
                        │
                        ▼
              ┌──── 等待 30s ────┐
              │                  │
              ▼                  ▼
         ┌─────────┐      ┌──────────┐
         │COMPLETED│      │ WAITING  │
         │ 正常    │      │ 继续等   │
         └────┬────┘      └────┬─────┘
              │                │
              │                ▼
              │          ┌──────────┐
              │          │WAITED_HALF│
              │          │ 30-60s   │
              │          │ dump预警  │
              │          └────┬─────┘
              │               │
              │               ▼
              │          ┌──────────┐
              │          │ OVERDUE  │
              │          │ >60s     │
              │          │ 杀进程！  │
              │          └──────────┘
              │
              └─── 下一轮 ───▶
```

### 3.5 在日志中查找 Watchdog 事件

```bash
# 搜索 Watchdog 相关日志
adb logcat -b system | grep -i watchdog

# 搜索 Watchdog 杀进程的日志
adb logcat -b system | grep "WATCHDOG KILLING"

# 查看 DropBox 中的 Watchdog 事件
adb shell dumpsys dropbox --print | grep -A 20 "watchdog"

# 查看 DropBox 中保存的异常事件列表
adb shell dumpsys dropbox
```

---

## 实验四：模拟 system_server 卡顿

### 4.1 方法一：通过 debuggerd 观察线程状态

这是一种安全的观察方法，不会真正导致 system_server 崩溃。

```bash
# 1. 找到 system_server 的 PID
adb shell pidof system_server

# 2. 查看 system_server 的所有线程
adb shell ps -T -p $(adb shell pidof system_server)

# 3. 查找 Watchdog 线程
adb shell ps -T -p $(adb shell pidof system_server) | grep -i watchdog

# 4. 查看 system_server 的线程堆栈
adb shell kill -3 $(adb shell pidof system_server)
# 等待几秒后查看 traces
adb shell cat /data/anr/traces.txt | head -200
```

### 4.2 方法二：分析 traces.txt 中的线程状态

发送 SIGQUIT 后，分析 traces.txt：

```bash
# 发送信号生成 traces
adb shell kill -3 $(adb shell pidof system_server)

# 等待 5 秒
sleep 5

# 拉取 traces 文件
adb pull /data/anr/traces.txt ./traces_systemserver.txt
```

**分析 traces.txt 的关键信息**：

```
----- pid 500 at 2024-01-15 10:30:00 -----
Cmd line: system_server

"main" prio=5 tid=1 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x... self=0x...
  | sysTid=500 nice=-2 cgrp=default sched=0/0 handle=0x...
  | state=S schedstat=( ... ) utm=... stm=...
  | stack=0x... stackSize=...
  | held mutexes=
  native: ...
  at android.os.MessageQueue.nativePollOnce(Native method)     ← 正常：等待消息
  at android.os.MessageQueue.next(MessageQueue.java:335)
  at android.os.Looper.loopOnce(Looper.java:161)
  at android.os.Looper.loop(Looper.java:288)
  at com.android.server.SystemServer.run(SystemServer.java:556)
  at com.android.server.SystemServer.main(SystemServer.java:363)

"Watchdog" prio=5 tid=25 Sleeping
  | group="main" sCount=1 ...
  at java.lang.Thread.sleep(Thread.java:450)
  at com.android.server.Watchdog.run(Watchdog.java:400)        ← Watchdog 在等待

"Binder:500_1" prio=5 tid=10 Native
  ...

"ActivityManager" prio=5 tid=15 Blocked              ← 注意：如果看到 Blocked
  at com.android.server.am.ActivityManagerService.xxx
  - waiting to lock <0x...> held by thread 20         ← 死锁嫌疑！

"WindowManager" prio=5 tid=20 Blocked
  at com.android.server.wm.WindowManagerService.xxx
  - waiting to lock <0x...> held by thread 15         ← 互相等待 = 死锁！
```

### 4.3 方法三：模拟 ANR 观察 AMS 反应

在任意 App 的主线程中制造卡顿：

```java
// 在某个 Activity 的 onCreate 中添加（仅测试用）
new Handler().postDelayed(() -> {
    // 在主线程中阻塞 10 秒
    try {
        Thread.sleep(10000);
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
}, 1000);

// 然后在卡顿期间触摸屏幕，5 秒后会触发 Input ANR
```

触发 ANR 后观察：

```bash
# 1. 查看 ANR 日志
adb logcat -b system | grep -i "anr"

# 2. 查看 AMS 记录的 ANR 信息
adb shell dumpsys activity anr

# 3. 查看 traces.txt
adb shell cat /data/anr/traces.txt

# 4. 查看 DropBox 中的 ANR 记录
adb shell dumpsys dropbox --print | grep -B 2 -A 30 "anr"
```

### 4.4 方法四：通过 AOSP 源码修改触发 Watchdog（高级）

> 以下操作需要 AOSP 编译环境，仅在开发设备上进行。

在 SystemServer 中注入延迟来触发 Watchdog：

```java
// 修改 SystemServer.java 或某个系统服务
// 在主线程 Handler 中投递一个耗时消息

// 示例：在 AMS 的某个 Binder 调用中注入延迟
@Override
public void someBinderMethod() {
    // 模拟耗时操作（阻塞 AMS 主线程）
    try {
        Thread.sleep(120_000);  // 阻塞 120 秒
    } catch (InterruptedException e) {}
}

// 结果：
// 30s 时 → Watchdog 发出 WAITED_HALF 预警
// 60s 时 → Watchdog 输出 "*** WATCHDOG KILLING SYSTEM PROCESS"
// system_server 被杀 → Zygote 重启 → 所有 App 重启
```

### 4.5 观察 Watchdog 触发后的日志

```bash
# Watchdog 触发时的典型日志序列：
# 1. 预警阶段（30s）
W Watchdog: *** WATCHDOG TIMER DETECTED; ...

# 2. dump 堆栈
I Process : Sending signal. PID: 500 SIG: 3

# 3. 杀进程（60s）
W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on ...
W Watchdog: main thread
W Watchdog: *** GOODBYE!

# 4. system_server 死亡
I Zygote  : Process 500 exited cleanly (10)

# 5. Zygote 检测到 system_server 死亡，重启系统
I Zygote  : System server process 500 has been killed, restarting
```

---

## 实验五：DropBoxManagerService 探索

### 5.1 什么是 DropBox

DropBox 是 Android 的异常事件持久化存储服务，记录各类系统异常：

```
DropBox 存储的事件类型：
├── system_server_crash    ← system_server Java 崩溃
├── system_server_wtf      ← system_server WTF 日志
├── system_server_anr      ← system_server ANR
├── system_server_watchdog ← Watchdog 触发事件
├── data_app_crash         ← 第三方 App 崩溃
├── data_app_anr           ← 第三方 App ANR
├── system_app_crash       ← 系统 App 崩溃
├── system_app_anr         ← 系统 App ANR
├── SYSTEM_TOMBSTONE       ← Native Crash
└── SYSTEM_BOOT            ← 系统启动
```

### 5.2 查看 DropBox 事件

```bash
# 查看 DropBox 中存储的所有事件摘要
adb shell dumpsys dropbox

# 查看最近的事件详情
adb shell dumpsys dropbox --print

# 按类型过滤
adb shell dumpsys dropbox --print --tag system_server_watchdog
adb shell dumpsys dropbox --print --tag data_app_crash
adb shell dumpsys dropbox --print --tag data_app_anr

# 查看 DropBox 文件存储位置
adb shell ls -la /data/system/dropbox/
```

### 5.3 稳定性监控中的应用

DropBox 是稳定性监控体系的数据源头：

```
DropBox 在稳定性建设中的位置：

异常事件发生
    │
    ▼
DropBoxManagerService 记录事件
    │ 写入 /data/system/dropbox/
    ▼
监控服务定期扫描 DropBox
    │
    ▼
聚合分析
    ├── 统计 Crash 率 / ANR 率
    ├── 识别 Top 问题
    └── 触发告警
```

---

## ✅ 实操检查清单

完成以下所有项目，今天的实操目标就达成了：

- [ ] 阅读 SystemServer.java 的 run() 方法，理解三阶段启动
- [ ] 定位到 startBootstrapServices() 中 AMS 和 PMS 的启动代码
- [ ] 执行 `dumpsys activity oom` 查看进程 OOM Adj 值
- [ ] 执行 `dumpsys window displays` 找到当前焦点窗口
- [ ] 执行 `dumpsys package <包名>` 查看包信息和权限
- [ ] 阅读 Watchdog.java 中的 HandlerChecker 类
- [ ] 理解 Watchdog run() 方法的四个阶段
- [ ] 向 system_server 发送 SIGQUIT 并分析 traces.txt
- [ ] 查看 DropBox 中的异常事件记录
- [ ] （高级）模拟 ANR 并用 dumpsys 分析

---

## 📝 实操笔记模板

```
=== Day 3 下午实操笔记 ===

1. SystemServer 源码阅读：
   - startBootstrapServices 中最先启动的服务: ______
   - startOtherServices 中启动 WMS 的代码位于第 ____ 行
   - AMS.systemReady() 回调中做了什么: ______

2. dumpsys 实操结果：
   - system_server 的 PID: ______
   - 前台应用的 OOM Adj: ______
   - 切到后台后 OOM Adj 变为: ______
   - 当前焦点窗口: ______

3. Watchdog 源码阅读：
   - DEFAULT_TIMEOUT 值: ______ ms
   - 监控的线程数量: ______
   - WAITED_HALF 状态触发时间: ______s
   - OVERDUE 状态触发时间: ______s

4. traces.txt 分析：
   - system_server 中线程总数: ______
   - 主线程状态: ______
   - Watchdog 线程状态: ______
   - 是否发现 Blocked 线程: ______

5. DropBox 事件：
   - 设备上记录了哪些类型的事件: ______
   - 最近一次异常事件是: ______

6. 关键理解：
   - Watchdog 为什么使用 Handler 投递消息来检测卡顿:
   - AMS 和 WMS 死锁在 traces.txt 中的特征:
```

---

## 💡 C++ 开发者视角

1. **Watchdog 的设计模式**：本质是一个心跳检测机制，类似于 C++ 网络编程中的 keepalive / heartbeat。你可以用 `std::condition_variable` + 超时等待实现类似功能。

2. **traces.txt 的生成**：`kill -3` (SIGQUIT) 在 ART 虚拟机中被拦截，触发线程堆栈 dump。这和 C++ 中用 `signal(SIGUSR1, handler)` 自定义信号处理类似。

3. **InputDispatcher (C++)**：ANR 的 5 秒计时实际在 Native 层 `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` 中实现，下午有余力可以阅读。

4. **锁竞争分析**：AMS-WMS 死锁问题，和 C++ 多线程中的 `std::mutex` 死锁分析方法一致——关键是找到锁的持有者和等待者，形成等待图。
