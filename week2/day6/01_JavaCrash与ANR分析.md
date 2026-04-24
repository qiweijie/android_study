# Day 6 上午：Java Crash 机制

## 📚 学习目标
- 理解 Java 异常传播链路：UncaughtExceptionHandler → AMS → DropBox
- 掌握常见 Crash 类型及根因分析
- 学会分析 traces.txt 中的线程状态与锁信息

---

## 一、Java Crash 处理链路

```
App 中发生未捕获异常

    │
    ▼
Thread.UncaughtExceptionHandler
    │ 默认实现：RuntimeInit$KillApplicationHandler
    ▼
AMS.handleApplicationCrash()
    │
    ├── 1. 写入 DropBox (data_app_crash)
    ├── 2. 写入 logcat -b crash
    ├── 3. 生成 traces.txt (如果需要)
    ├── 4. 弹出 "应用停止运行" 对话框
    └── 5. 杀死进程 Process.killProcess()
```

### 1.1 异常处理源码路径

```
frameworks/base/core/java/com/android/internal/os/
├── RuntimeInit.java                    # KillApplicationHandler
│   └── handleApplicationCrash()        # 调用 AMS
├── ZygoteInit.java                     # Zygote 异常处理
└── LoggingHandler.java                 # 日志记录

frameworks/base/services/core/java/com/android/server/am/
├── AppErrors.java                      # Crash/ANR 处理核心
│   ├── crashApplication()              # 处理应用 Crash
│   └── handleShowAppErrorUi()          # 弹出错误对话框
└── ActivityManagerService.java
    └── handleApplicationCrash()        # AMS 入口
```

---

## 二、常见 Crash 类型

### 2.1 分类与频率

```
高频 Crash 类型排行：

排名  类型                        占比    常见原因
──────────────────────────────────────────────────
 1   NullPointerException        ~35%   空对象引用
 2   IllegalStateException       ~12%   状态错误（如 Activity 已销毁）
 3   IllegalArgumentException    ~8%    参数非法
 4   IndexOutOfBoundsException   ~7%    数组/列表越界
 5   ClassCastException          ~5%    类型转换错误
 6   OutOfMemoryError            ~5%    Java 堆内存不足
 7   DeadObjectException         ~4%    远端 Binder 对象已死亡
 8   SecurityException           ~3%    权限不足
 9   ClassNotFoundException      ~3%    类找不到
10   RuntimeException (其他)     ~18%   各种运行时错误
```

### 2.2 重点 Crash 分析

#### DeadObjectException — Binder 相关

```
// 当 Server 端进程死亡时，Client 调用会抛出
try {
    mService.doSomething();  // 远程调用
} catch (DeadObjectException e) {
    // Server 进程已经死亡
    // Binder Driver 检测到远端已不存在
    // 返回 DEAD_OBJECT 错误码
}

常见场景：
• 绑定的 Service 进程被 LMK 杀死
• system_server 崩溃后所有 Binder 调用都会失败
• 远程进程被用户强制停止
```

#### OutOfMemoryError — 内存溢出

```
java.lang.OutOfMemoryError: Failed to allocate a xxx byte allocation

分析步骤：
1. 确认是 Java Heap 还是 Native Heap
   → dumpsys meminfo <pid> 查看 Heap 使用
2. 查看 hprof dump（如果有）
   → MAT 或 Android Studio Profiler 分析
3. 常见根因：
   → Bitmap 未及时回收
   → 集合类无限增长
   → 内存泄漏（Activity/Fragment 被长生命周期对象引用）
```

---

## 三、traces.txt 分析

### 3.1 线程状态

```
traces.txt 中的线程状态含义：

状态               含义                   稳定性关注
──────────────────────────────────────────────────
RUNNABLE           正在执行              正常
SLEEPING           Thread.sleep()        可能是故意等待
WAITING            Object.wait()         可能在等锁
TIMED_WAITING      带超时的等待           一般正常
BLOCKED            等待 synchronized      可能死锁 ⚠️
NATIVE             在 Native 代码中       可能在 Binder 调用
SUSPENDED          被 GC 或调试器暂停     关注 GC 频率
```

### 3.2 死锁检测模式

```
死锁特征 — 两个线程互相等待对方持有的锁：

"Thread-A" prio=5 tid=10 Blocked
  - waiting to lock <0xAAA> (a com.example.LockA)
      held by thread 11        ← 等 Thread-B 的锁
  - locked <0xBBB> (a com.example.LockB)

"Thread-B" prio=5 tid=11 Blocked
  - waiting to lock <0xBBB> (a com.example.LockB)
      held by thread 10        ← 等 Thread-A 的锁
  - locked <0xAAA> (a com.example.LockA)

→ Thread-A 持有 LockB，等待 LockA
→ Thread-B 持有 LockA，等待 LockB
→ 经典 AB-BA 死锁！
```

### 3.3 Crash 日志分析模板

```
拿到一个 Crash 日志后的分析步骤：

1. 确认异常类型和消息
   FATAL EXCEPTION: main
   java.lang.NullPointerException: Attempt to invoke virtual method
       'xxx' on a null object reference

2. 找到 at 行中的第一个「自己代码」
   at com.example.app.MyActivity.onClick(MyActivity.java:42)

3. 回溯调用链
   → 谁调用了这个方法
   → 哪个对象为 null

4. 查看线程名
   → main = 主线程（ANR 风险）
   → Binder:xxx = Binder 调用中
   → AsyncTask / Thread-N = 后台线程

5. 检查是否有关联日志
   → 搜索同一时间点的 system log
   → 是否有 OOM / LMK 触发
```

---

## ✅ 本节自检清单

- [ ] 能描述 Java Crash 从发生到进程被杀的完整链路
- [ ] 能识别 traces.txt 中的死锁模式
- [ ] 理解 DeadObjectException 的含义和场景
- [ ] 掌握 Crash 日志的系统分析步骤

---

## 💡 思考题

1. 自定义 UncaughtExceptionHandler 能捕获所有 Java 异常吗？有什么限制？
2. system_server 中的 Java Crash 和普通 App 的处理有什么不同？
3. 为什么 DeadObjectException 在低内存设备上更常见？
