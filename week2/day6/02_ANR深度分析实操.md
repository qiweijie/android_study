# Day 6 下午：ANR 深度分析实操

## 🎯 实操目标
- 掌握四种 ANR 的触发机制和超时阈值
- 学会分析 ANR 日志：traces.txt、Reason、CPU、iowait
- 掌握 Binder 调用链追踪与跨进程死锁分析
- 完成 3 个 ANR 案例的构造与分析

---

## 实验一：ANR 触发机制

### 1.1 四种 ANR 类型与超时阈值

```
类型                    前台超时    后台超时    判定者
──────────────────────────────────────────────────────
Input ANR               5s         5s         InputDispatcher (Native)
BroadcastReceiver ANR   10s        60s        AMS (BroadcastQueue)
Service ANR             20s        200s       AMS (ActiveServices)
ContentProvider ANR     10s        10s        AMS
──────────────────────────────────────────────────────
```

### 1.2 构造 Input ANR

```java
// 在 Activity 中阻塞主线程
public class AnrDemoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn_input_anr).setOnClickListener(v -> {
            // 在主线程阻塞 10 秒 → 触发 Input ANR (5s)
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {}
        });
    }
}
// 点击按钮后再触摸屏幕，5 秒后触发 ANR
```

### 1.3 构造 BroadcastReceiver ANR

```java
public class SlowReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // 在 onReceive 中阻塞 15 秒 → 触发前台广播 ANR (10s)
        try {
            Thread.sleep(15_000);
        } catch (InterruptedException e) {}
    }
}

// 发送前台广播触发
Intent intent = new Intent("com.example.SLOW_ACTION");
sendBroadcast(intent);
```

### 1.4 构造 Service ANR

```java
public class SlowService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 在主线程阻塞 25 秒 → 触发前台 Service ANR (20s)
        try {
            Thread.sleep(25_000);
        } catch (InterruptedException e) {}
        return START_NOT_STICKY;
    }
}
```

---

## 实验二：ANR 日志分析

### 2.1 收集 ANR 日志

```bash
# 触发 ANR 后收集日志

# 1. traces.txt — 最重要
adb shell cat /data/anr/traces.txt > traces.txt

# 2. logcat 中的 ANR 信息
adb logcat -b system -d | grep -A 20 "ANR in"

# 3. DropBox 中的 ANR 记录
adb shell dumpsys dropbox --print --tag data_app_anr
```

### 2.2 分析 traces.txt

```
traces.txt 关键段落分析：

===== 段落 1：进程和时间 =====
----- pid 12345 at 2024-01-15 14:30:00 -----
Cmd line: com.example.app

===== 段落 2：主线程状态 =====
"main" prio=5 tid=1 Sleeping              ← 关键！主线程状态
  | group="main" sCount=1 ucsCount=0
  | sysTid=12345 nice=-10
  | state=S schedstat=( 50000000 20000000 100 )
  | held mutexes=
  at java.lang.Thread.sleep(Native method)     ← 罪魁祸首
  at com.example.app.MainActivity$1.onClick(MainActivity.java:25)
  at android.view.View.performClick(View.java:7448)

分析要点：
→ 主线程状态: Sleeping (Thread.sleep)
→ 调用栈: onClick → Thread.sleep
→ 结论: 主线程被 sleep 阻塞导致 ANR
```

### 2.3 分析 logcat 中的 ANR 信息

```
关键日志格式：

E ActivityManager: ANR in com.example.app (com.example.app/.MainActivity)
E ActivityManager:   PID: 12345
E ActivityManager:   Reason: Input dispatching timed out (xxx)
E ActivityManager:   Load: 2.5 / 1.8 / 1.2
E ActivityManager:   CPU usage from 10s to 0s ago (2024-01-15 14:30:00):
E ActivityManager:     45% 500/system_server: 30% user + 15% kernel
E ActivityManager:     12% 12345/com.example.app: 8% user + 4% kernel
E ActivityManager:     8% TOTAL: 5% user + 3% kernel; 2% iowait

分析：
→ Reason: Input dispatching timed out → Input ANR
→ CPU usage: 总 8% → 不是 CPU 竞争问题
→ iowait: 2% → 不是 IO 阻塞
→ 结论: 应用自身阻塞主线程
```

### 2.4 ANR 根因分类决策树

```
ANR 发生
  │
  ├── CPU usage 很高 (>80%)？
  │     ├── 是 → CPU 竞争/频繁 GC
  │     │   → 检查哪个进程占用高
  │     │   → 检查 GC 日志
  │     └── 否 ↓
  │
  ├── iowait 很高 (>30%)？
  │     ├── 是 → IO 阻塞
  │     │   → 主线程是否有文件/数据库操作
  │     │   → 是否在 SharedPreferences.commit()
  │     └── 否 ↓
  │
  ├── main thread 状态是 BLOCKED？
  │     ├── 是 → 锁竞争/死锁
  │     │   → 查看 "waiting to lock" 和 "held by"
  │     │   → 是否有循环等待
  │     └── 否 ↓
  │
  ├── main thread 在 Binder 调用中？
  │     ├── 是 → Binder 调用阻塞
  │     │   → 远端服务是否卡住
  │     │   → Binder 线程是否耗尽
  │     └── 否 ↓
  │
  └── main thread 在 sleep/自身逻辑中？
        └── 是 → 应用自身代码阻塞
            → 检查调用栈中的具体方法
```

---

## 实验三：Binder 调用链追踪

### 3.1 识别 Binder 阻塞

```
traces.txt 中 Binder 阻塞的特征：

"main" prio=5 tid=1 Native
  at android.os.BinderProxy.transactNative(Native method)     ← Binder 调用
  at android.os.BinderProxy.transact(Binder.java:1127)
  at android.app.IActivityManager$Stub$Proxy.startActivity(...)
  ...

解读：
→ 主线程在 BinderProxy.transactNative 处阻塞
→ 正在调用 AMS 的 startActivity
→ 需要检查 AMS 线程是否卡住
```

### 3.2 跨进程死锁分析

```bash
# 同时查看 App 和 system_server 的 traces
adb shell kill -3 $(adb shell pidof system_server)
sleep 3
adb shell cat /data/anr/traces.txt > traces_full.txt

# 在 traces 中搜索：
# 1. App 的主线程是否在等待 system_server
# 2. system_server 的对应线程是否在等待其他锁
```

### 3.3 Binder 线程耗尽分析

```bash
# 查看进程的 Binder 线程状态
adb shell cat /proc/$(adb shell pidof com.example.app)/status | grep Threads

# 在 traces.txt 中搜索所有 Binder 线程
grep "Binder:" traces.txt

# 如果所有 Binder 线程都在 BLOCKED/WAITING 状态 → Binder 线程耗尽
```

---

## 实验四：3 个 ANR 案例分析

### 案例 1：主线程 IO 阻塞

```
现象: 应用打开数据库时 ANR
traces.txt:
  "main" tid=1 Waiting
    at android.database.sqlite.SQLiteDatabase.lock
    at android.database.sqlite.SQLiteDatabase.execSQL
    at com.example.app.DataHelper.init(DataHelper.java:55)
CPU usage: iowait=45%

根因: 主线程直接执行数据库操作，遇到 IO 等待
修复: 将数据库操作移到后台线程
```

### 案例 2：锁竞争

```
现象: 应用在特定操作后 ANR
traces.txt:
  "main" tid=1 Blocked
    - waiting to lock <0xABC> held by thread 15
  "Thread-15" tid=15 Runnable
    - locked <0xABC>
    at com.example.app.DataProcessor.heavyWork()

根因: 后台线程长期持有锁，主线程等待同一把锁
修复: 减小锁粒度，或使用读写锁
```

### 案例 3：Binder 调用超时

```
现象: 应用调用系统服务时 ANR
traces.txt:
  "main" tid=1 Native
    at android.os.BinderProxy.transactNative
    at ...IPackageManager$Stub$Proxy.getPackageInfo(...)
  system_server 中:
    "PackageManager" tid=20 Blocked
      - waiting to lock held by thread 15
      "Binder:500_3" tid=15 Sleeping

根因: PMS 线程持锁卡住，App 的 Binder 调用被阻塞
修复: 系统侧需优化 PMS 锁竞争
```

---

## ✅ 实操检查清单

- [ ] 成功构造并触发一次 Input ANR
- [ ] 收集了 traces.txt 并找到主线程阻塞原因
- [ ] 分析了 logcat 中的 CPU usage 和 iowait
- [ ] 理解 ANR 根因分类决策树
- [ ] 分析了 Binder 阻塞类型的 ANR
- [ ] 完成 3 个 ANR 案例分析

---

## 📝 实操笔记模板

```
=== Day 6 下午实操笔记 ===

ANR 案例分析记录：

案例 1:
  类型: Input / Broadcast / Service
  主线程状态: ______
  CPU usage: ______%
  iowait: ______%
  根因: ______
  修复方案: ______

案例 2: ...
案例 3: ...
```
