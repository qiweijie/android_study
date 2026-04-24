# Day 8 下午：Watchdog & 系统级稳定性实操

## 🎯 实操目标
- 阅读 Watchdog.java 源码的 HandlerChecker 和超时判定逻辑
- 分析 Watchdog 触发的日志样本
- 了解 lmkd 配置和观察 LMK 行为
- 探索 DropBoxManagerService 的异常事件存储

---

## 实验一：Watchdog.java 源码阅读

### 1.1 关键代码定位

```bash
# 源码路径
frameworks/base/services/core/java/com/android/server/Watchdog.java

# 重点阅读：
# 1. 构造函数 → 注册了哪些 HandlerChecker
# 2. run() 方法 → 检查循环的完整逻辑
# 3. HandlerChecker 内部类 → 检测原理
# 4. evaluateCheckerCompletionLocked() → 状态判定
```

### 1.2 跟踪阅读要点

```
阅读顺序建议：

1. Watchdog() 构造函数
   → 找到所有 mHandlerCheckers.add(...) 调用
   → 记录监控了哪些线程、超时时间

2. HandlerChecker.scheduleCheckLocked()
   → 如何向目标线程投递检查消息
   → mCompleted 标志的用法

3. HandlerChecker.run()
   → 被目标线程执行时做了什么
   → 只是设置 mCompleted = true

4. HandlerChecker.getCompletionStateLocked()
   → 四种状态的判定逻辑
   → COMPLETED / WAITING / WAITED_HALF / OVERDUE

5. Watchdog.run() 主循环
   → 投递 → 等待 → 检查 → 处理超时
   → dump 堆栈 → 写 DropBox → 杀进程
```

---

## 实验二：分析 Watchdog 日志

### 2.1 搜索 Watchdog 相关日志

```bash
# 在设备上搜索 Watchdog 日志
adb logcat -b system -d | grep -i "watchdog"

# 搜索 Watchdog 杀进程的日志
adb logcat -b system -d | grep "WATCHDOG KILLING"

# 查看最近的 system_server 重启
adb logcat -b events -d | grep "boot_progress"
```

### 2.2 Watchdog 触发日志样本分析

```
典型的 Watchdog 触发日志：

W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler
            on foreground thread (foreground thread)
W Watchdog: foreground thread annotations:
W Watchdog:   - Blocked in handler on foreground thread
              (foreground thread): HandlerChecker
W Watchdog: "foreground thread" prio=5 tid=18 Blocked
W Watchdog:   at com.android.server.am.ActivityManagerService.broadcastIntent
W Watchdog:   - waiting to lock <0x0a1b2c3d> (ActivityManagerService)
              held by thread 25
W Watchdog:   ...
W Watchdog: *** GOODBYE!

分析步骤：
1. 哪个线程卡住了？ → foreground thread
2. 卡在哪里？ → AMS.broadcastIntent()
3. 等什么锁？ → AMS 锁 <0x0a1b2c3d>
4. 谁持有锁？ → thread 25
5. thread 25 在做什么？ → 在 traces.txt 中查看
```

### 2.3 查看 DropBox 中的 Watchdog 事件

```bash
# 查看所有 Watchdog 事件
adb shell dumpsys dropbox --print --tag system_server_watchdog

# 查看 DropBox 中所有类型的事件统计
adb shell dumpsys dropbox | grep -E "Tag:|Count:"
```

---

## 实验三：lmkd 配置与观察

### 3.1 查看 lmkd 配置

```bash
# 查看 lmkd 相关系统属性
adb shell getprop | grep lmk
# ro.lmk.low        → 低压力阈值
# ro.lmk.medium     → 中压力阈值
# ro.lmk.critical   → 高压力阈值

# 查看 OOM 配置（旧版内核 LMK）
adb shell cat /sys/module/lowmemorykiller/parameters/minfree
adb shell cat /sys/module/lowmemorykiller/parameters/adj
```

### 3.2 观察 LMK 行为

```bash
# 监控内核 LMK 日志
adb shell dmesg -w | grep -i "lowmemory\|lmk\|oom"

# 查看当前内存状态
adb shell cat /proc/meminfo | head -10

# 查看各进程的 OOM adj
adb shell dumpsys activity oom

# 监控进程被杀
adb logcat -b events | grep "am_proc_died"

# 输出格式：
# am_proc_died: [0,12345,com.example.app,900,16]
#                UID PID    进程名          adj  原因
```

### 3.3 模拟内存压力

```bash
# 观察多开 App 时的进程回收
# 步骤：
# 1. 打开 10+ 个 App
# 2. 持续监控 am_proc_died 事件
# 3. 对比被杀进程的 adj 值

adb logcat -b events | grep "am_proc_died"

# 同时观察内存变化
watch -n 1 'adb shell cat /proc/meminfo | head -5'
```

---

## 实验四：DropBoxManagerService 深入

### 4.1 查看 DropBox 存储

```bash
# DropBox 文件存储位置
adb shell ls -la /data/system/dropbox/

# 文件命名格式：
# <tag>@<timestamp>.txt.gz
# 例如：data_app_crash@1705305600000.txt.gz
#        system_server_watchdog@1705305600000.txt.gz

# 查看事件统计
adb shell dumpsys dropbox

# 按标签查看详情
adb shell dumpsys dropbox --print --tag data_app_crash
adb shell dumpsys dropbox --print --tag data_app_anr
adb shell dumpsys dropbox --print --tag system_server_crash
adb shell dumpsys dropbox --print --tag SYSTEM_TOMBSTONE
```

### 4.2 DropBox 与稳定性监控

```
DropBox 在稳定性监控中的数据流：

App/系统异常发生
    │
    ▼
AMS/debuggerd 写入 DropBox
    │ mDropBoxManager.addText(tag, data)
    ▼
DropBoxManagerService 持久化存储
    │ /data/system/dropbox/<tag>@<time>.txt.gz
    ▼
监控服务定期扫描
    │ ContentObserver 监听 DropBox 变更
    ▼
统计与告警
    ├── Crash 率 = crash 事件数 / 总设备数
    ├── ANR 率 = anr 事件数 / 总设备数
    ├── Watchdog 率 = watchdog 事件数
    └── 超过阈值 → 告警
```

---

## ✅ 实操检查清单

- [ ] 阅读了 Watchdog.java 中的 HandlerChecker 实现
- [ ] 理解了 run() 方法的四阶段检测逻辑
- [ ] 分析了一条 Watchdog 触发日志的完整信息
- [ ] 查看了 lmkd 配置和 OOM adj
- [ ] 用 `am_proc_died` 事件观察了进程被杀
- [ ] 查看了 DropBox 中存储的异常事件
- [ ] 理解了 DropBox 在稳定性监控中的作用

---

## 📝 实操笔记模板

```
=== Day 8 下午实操笔记 ===

1. Watchdog 源码:
   - 监控的线程数: ______
   - DEFAULT_TIMEOUT: ______ms
   - Monitor 监控的锁: AMS / WMS / ______

2. lmkd 观察:
   - ro.lmk.low: ______
   - 内存压力测试中被杀的进程: ______
   - 被杀进程的 adj: ______

3. DropBox 事件:
   - 设备上的异常事件类型: ______
   - data_app_crash 数量: ______
   - system_server_watchdog 数量: ______
```
