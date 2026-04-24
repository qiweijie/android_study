# Day 8 上午：Watchdog & 系统级稳定性机制

## 📚 学习目标
- 深入理解 Watchdog 机制：监控线程、检查周期、触发条件
- 理解 system_server 崩溃的影响链
- 掌握 LMK / lmkd / OOM Adj 进程回收策略
- 理解进程保活与回收机制

---

## 一、Watchdog 深入

### 1.1 完整工作流程

```
Watchdog 生命周期：

system_server 启动
    │
    ▼
Watchdog.getInstance().init()
    │ 注册 HandlerChecker 列表
    │ 注册 Monitor 列表（锁监控）
    ▼
Watchdog.start()  → 启动 Watchdog 线程
    │
    ▼
┌─── 无限循环 ────────────────────────────────────┐
│                                                  │
│  1. scheduleCheckLocked()                        │
│     → 向每个被监控线程的 Handler 投递 Runnable    │
│     → 如果线程正常运行，Runnable 会被执行         │
│                                                  │
│  2. 等待 30 秒                                    │
│                                                  │
│  3. evaluateCheckerCompletionLocked()             │
│     ├── COMPLETED (所有正常) → 继续               │
│     ├── WAITING (< 30s) → 继续等待               │
│     ├── WAITED_HALF (30-60s) → dump 线程堆栈预警  │
│     └── OVERDUE (> 60s) → *** KILLING ***        │
│                                                  │
│  如果 OVERDUE:                                    │
│  → dump 所有线程堆栈                              │
│  → 写入 DropBox (system_server_watchdog)          │
│  → Process.killProcess(myPid)                    │
│  → System.exit(10)                               │
│                                                  │
└──────────────────────────────────────────────────┘
```

### 1.2 Monitor 机制 — 锁监控

```java
// Watchdog 还能监控锁是否被长期持有
// 各系统服务会注册 Monitor

public interface Monitor {
    void monitor();  // 尝试获取锁，获取不到就说明卡住了
}

// 示例：AMS 的 Monitor 实现
class ActivityManagerService implements Watchdog.Monitor {
    @Override
    public void monitor() {
        synchronized (this) { }  // 尝试获取 AMS 锁
        // 如果 AMS 锁被其他线程长期持有
        // 这里就会卡住 → Watchdog 检测到超时
    }
}

// 注册：
Watchdog.getInstance().addMonitor(mActivityManagerService);
Watchdog.getInstance().addMonitor(mWindowManagerService);
```

### 1.3 Watchdog 的检测目标

```
HandlerChecker (线程消息队列检测):
→ 检测线程的 MessageQueue 是否正常处理消息
→ 覆盖：main, fg, ui, io, display, animation 等线程

Monitor (锁检测):
→ 检测关键锁是否能正常获取
→ 覆盖：AMS 锁, WMS 锁, PMS 锁, PowerMS 锁等

两种检测覆盖了 system_server 卡死的主要原因：
1. 线程忙（消息处理不过来）
2. 死锁（线程互相等待对方的锁）
```

---

## 二、system_server 崩溃影响链

```
system_server 崩溃后的连锁反应：

system_server 进程死亡
    │
    ▼
Zygote 检测到子进程死亡 (waitpid)
    │ Zygote 是 system_server 的父进程
    ▼
Zygote 调用 exit() 退出
    │ 因为 system_server 死亡意味着系统无法运行
    ▼
init 检测到 Zygote 死亡 (init.rc: restart)
    │
    ▼
init 重启 Zygote
    │
    ▼
Zygote 重新 fork 出 system_server
    │
    ▼
system_server 重新启动所有系统服务
    │ 这个过程需要 10-30 秒
    ▼
所有 App 进程已经死亡（Zygote 重启导致）
    │
    ▼
用户看到类似"重启"的效果
    │ 锁屏界面 → 重新加载桌面

总耗时：通常 10-30 秒
用户感知：等同于系统重启
```

---

## 三、LMK / lmkd — 低内存管理

### 3.1 Low Memory Killer 演进

```
Android 低内存管理的演进：

Android 8 以前:
  Linux 内核 LMK Driver (/drivers/staging/android/lowmemorykiller.c)
  → 在内核中直接杀进程
  → 配置: /sys/module/lowmemorykiller/parameters/

Android 9+:
  lmkd 守护进程 (用户空间)
  → 更灵活的杀进程策略
  → 支持 PSI (Pressure Stall Information)
  → 配置: prop 属性

源码路径:
system/memory/lmkd/
├── lmkd.cpp          # lmkd 主逻辑
├── statslog.cpp       # 统计日志
└── Android.bp
```

### 3.2 lmkd 工作原理

```
lmkd 工作流程：

内核内存压力通知
    │ 通过 cgroup memory.pressure_level
    │ 或 PSI (Pressure Stall Information)
    ▼
lmkd 收到内存压力事件
    │
    ▼
根据压力等级确定杀进程策略
    │
    ├── 低压力 → 杀 adj >= 900 的缓存进程
    ├── 中压力 → 杀 adj >= 700 的进程
    └── 高压力 → 杀 adj >= 200 的进程
    │
    ▼
选择目标进程 (adj 最高的)
    │
    ▼
kill(pid, SIGKILL)
    │
    ▼
释放内存
```

### 3.3 adj 动态调整

```
进程 adj 不是固定的，AMS 会根据进程状态动态调整：

场景                     adj 变化
───────────────────────────────────
App 来到前台              adj → 0 (FOREGROUND)
App 切到后台              adj → 700 (PREVIOUS)
App 长时间后台            adj → 900+ (CACHED)
App 播放音乐（后台）      adj → 200 (PERCEPTIBLE)
App 有前台 Service        adj → 200 (PERCEPTIBLE)
App 绑定了前台 App        adj 跟随被绑定的 App

查看：
adb shell dumpsys activity oom
adb shell cat /proc/<pid>/oom_score_adj
```

---

## 四、进程保活与回收

### 4.1 Cached Processes

```
缓存进程 = 后台不可见、无活跃组件的进程

目的：加速 App 再次启动（热启动 vs 冷启动）
管理：由 AMS 根据 LRU 列表管理
上限：通常 32 个缓存进程（可配置）
回收：内存不足时由 lmkd 按 adj 从大到小杀

查看缓存进程列表：
adb shell dumpsys activity processes | grep -A 2 "cached"
```

### 4.2 Empty Processes

```
空进程 = 没有任何活跃组件（Activity/Service/Provider/Receiver）

特点：
• adj 最高（999），最先被杀
• 存在的唯一意义是缓存进程信息
• 系统随时可以杀掉它
```

---

## ✅ 本节自检清单

- [ ] 能描述 Watchdog 的完整检测流程
- [ ] 理解 HandlerChecker 和 Monitor 两种检测方式
- [ ] 能说清 system_server 崩溃后的连锁反应
- [ ] 理解 lmkd 的工作原理和杀进程策略
- [ ] 能解释 adj 动态调整的规则

---

## 💡 思考题

1. 如果 Watchdog 线程自身卡住了怎么办？
2. lmkd 杀进程时如何保证不杀掉重要进程？
3. 为什么 Android 9 以后把 LMK 从内核移到用户空间？
