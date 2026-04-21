# Day 2 下午：Binder IPC 实战练习

## 🎯 实操目标
- 阅读 servicemanager 源码，理解 Binder 服务注册中心的 C++ 实现
- 用调试命令查看系统中已注册的 Binder 服务
- 运行 BinderDemo 项目，完成一次完整的 AIDL 跨进程调用
- 使用 Binder 调试工具观察事务和线程状态

---

## 实验一：阅读 servicemanager 源码

### 1.1 源码位置

servicemanager 是 Android 系统中第一个启动的 Binder 服务，它是所有 Binder 通信的「起点」。

```
AOSP 源码路径：
frameworks/native/cmds/servicemanager/
├── main.cpp                  # 入口 main 函数
├── ServiceManager.cpp        # 核心实现
├── ServiceManager.h          # 头文件
├── Access.cpp                # 权限检查
└── Android.bp                # 构建配置
```

### 1.2 关键代码阅读

#### main.cpp — 启动流程

```cpp
// frameworks/native/cmds/servicemanager/main.cpp
int main(int argc, char** argv) {
    // 1. 打开 Binder 驱动
    sp<ProcessState> ps = ProcessState::initWithDriver("/dev/binder");

    // 2. 设为 Binder 上下文管理者（handle = 0 的特殊服务）
    ps->setThreadPoolMaxThreadCount(0);
    ps->becomeContextManager();

    // 3. 创建 ServiceManager 实例
    sp<ServiceManager> manager = sp<ServiceManager>::make(
        std::make_unique<Access>());

    // 4. 注册自身为 Binder 服务
    IPCThreadState::self()->setTheContextObject(manager);

    // 5. 进入循环，处理 Binder 请求
    ps->startThreadPool();
    IPCThreadState::self()->joinThreadPool();
}
```

**关键点理解**：
- servicemanager 的 Binder handle 固定为 `0`
- 所有进程都可以通过 handle 0 找到 servicemanager
- 它相当于 Binder 世界的 "DNS"

#### ServiceManager.cpp — 核心方法

```cpp
// 注册服务
Status ServiceManager::addService(const std::string& name,
                                   const sp<IBinder>& binder,
                                   bool allowIsolated,
                                   int32_t dumpPriority) {
    // 1. 权限检查 - 并非所有进程都能注册服务
    if (!mAccess->canAdd(callingContext, name)) {
        return Status::fromExceptionCode(Status::EX_SECURITY);
    }

    // 2. 保存到服务注册表 (map)
    mNameToService[name] = Service {
        .binder = binder,
        .allowIsolated = allowIsolated,
        .dumpPriority = dumpPriority,
        .debugPid = ctx.debugPid,
    };

    return Status::ok();
}

// 查询服务
Status ServiceManager::getService(const std::string& name,
                                   sp<IBinder>* outBinder) {
    // 1. 在注册表中查找
    auto it = mNameToService.find(name);
    if (it == mNameToService.end()) {
        *outBinder = nullptr;
        return Status::ok();
    }

    // 2. 返回 Binder 引用
    *outBinder = it->second.binder;
    return Status::ok();
}
```

### 1.3 C++ 开发者关注点

作为 C++ 开发者，重点关注以下设计模式：

| 概念 | servicemanager 中的体现 | C++ 关联知识 |
|------|------------------------|-------------|
| 智能指针 | `sp<IBinder>` | 类似 `std::shared_ptr`，引用计数 |
| 线程模型 | `joinThreadPool()` | 事件循环 / epoll |
| 设计模式 | Binder handle 0 | 单例注册中心 |
| 内存管理 | Parcel 序列化 | 类似 protobuf 编解码 |

---

## 实验二：查看系统 Binder 服务

### 2.1 列出所有已注册服务

```bash
# 连接设备
adb shell service list
```

**预期输出示例**：
```
Found 134 services:
0	activity: [android.app.IActivityManager]
1	package: [android.content.pm.IPackageManager]
2	window: [android.view.IWindowManager]
3	alarm: [android.app.IAlarmManager]
4	input: [android.hardware.input.IInputManager]
5	power: [android.os.IPowerManager]
...
```

### 2.2 理解常见服务

| 服务名称 | 类名 | 作用 |
|---------|------|------|
| `activity` | ActivityManagerService | 四大组件管理、进程管理 |
| `window` | WindowManagerService | 窗口管理、焦点控制 |
| `package` | PackageManagerService | APK 安装/权限管理 |
| `input` | InputManagerService | 输入事件分发 |
| `power` | PowerManagerService | 电源管理 |
| `display` | DisplayManagerService | 显示管理 |
| `SurfaceFlinger` | SurfaceFlinger | 图形合成（Native 服务） |

### 2.3 与服务交互

```bash
# 调用 AMS 的 dumpsys
adb shell dumpsys activity

# 查看所有正在运行的进程
adb shell dumpsys activity processes | head -50

# 查看某个服务的接口描述符
adb shell service check activity
# 输出: Service activity: found

# 直接通过 service 命令调用
adb shell service call activity 1  # 调用方法 code=1
```

### 2.4 练习题

1. 统计系统中总共有多少个 Binder 服务？
2. 找出哪些是 Java 层服务（IXxxManager），哪些是 Native 层服务
3. 用 `adb shell dumpsys -l` 列出所有支持 dumpsys 的服务

---

## 实验三：运行 BinderDemo 项目

### 3.1 项目概述

BinderDemo 是一个演示 Binder 跨进程通信的 Android 项目：

```
week1/day2/BinderDemo/
├── app/src/main/
│   ├── aidl/com/example/binderdemo/
│   │   └── ICalculatorService.aidl    ← AIDL 接口定义
│   ├── java/com/example/binderdemo/
│   │   ├── CalculatorService.java     ← Server（运行在 :remote 进程）
│   │   └── MainActivity.java          ← Client（运行在默认进程）
│   ├── res/layout/
│   │   └── activity_main.xml          ← UI 布局
│   └── AndroidManifest.xml            ← 配置进程隔离
└── build.gradle
```

### 3.2 运行步骤

#### Step 1：用 Android Studio 打开项目

```
File → Open → 选择 week1/day2/BinderDemo 目录
等待 Gradle 同步完成
```

#### Step 2：连接设备并运行

```bash
# 确认设备连接
adb devices

# 运行 App（或直接点 Android Studio 的 Run 按钮）
```

#### Step 3：操作流程

1. **点击「绑定服务」** → 建立 Binder 连接
2. **输入两个数字** → 如 42 和 18
3. **点击「加法 (IPC)」** → 触发跨进程调用
4. **点击「查看双端进程信息」** → 对比 PID

#### Step 4：观察 Logcat 输出

```bash
# 打开终端，过滤 BinderDemo 的日志
adb logcat -s "BinderDemo-Client:D" "BinderDemo-Service:D"
```

**预期输出**：
```
D/BinderDemo-Client: Activity 创建 | PID: 12345
D/BinderDemo-Client: 正在绑定远程服务...
D/BinderDemo-Service: Service 创建 | PID: 12378          ← 不同的 PID！
D/BinderDemo-Service: onBind() | Client 正在绑定服务
D/BinderDemo-Client: 服务已连接!
D/BinderDemo-Client: IBinder 类型: android.os.BinderProxy  ← 跨进程的证据
D/BinderDemo-Service: add() 被调用: 42 + 18 | 当前线程: Binder:12378_1 | PID: 12378
D/BinderDemo-Client: add() 跨进程调用成功: 60 | 耗时: 150000 ns
```

### 3.3 核心代码解读

#### AIDL 接口定义

```java
// ICalculatorService.aidl
interface ICalculatorService {
    int add(int a, int b);            // 跨进程加法
    int subtract(int a, int b);       // 跨进程减法
    String getServerProcessInfo();    // 获取服务端进程信息
}
```

编译后自动生成：
- `ICalculatorService.Stub` — Server 端继承
- `ICalculatorService.Stub.Proxy` — Client 端使用

#### 关键配置：进程隔离

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".CalculatorService"
    android:process=":remote"         ← 关键！使 Service 运行在独立进程
    android:exported="false" />
```

#### Client 端绑定逻辑

```java
// 绑定服务
bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

// 连接回调
public void onServiceConnected(ComponentName name, IBinder service) {
    // 跨进程时，Stub.asInterface() 返回 Proxy 对象
    mService = ICalculatorService.Stub.asInterface(service);
}

// 调用（看起来像本地方法，实际是跨进程）
int result = mService.add(42, 18);
```

### 3.4 动手扩展练习

尝试自己添加一个新的 AIDL 方法：

```java
// 在 ICalculatorService.aidl 中添加
int multiply(int a, int b);
```

然后：
1. 在 `CalculatorService.java` 中实现 `multiply()` 方法
2. 在 `MainActivity.java` 中添加调用按钮
3. 在 `activity_main.xml` 中添加乘法按钮
4. 运行并验证跨进程调用是否正常

---

## 实验四：Binder 调试工具

### 4.1 查看 Binder 状态

```bash
# 查看 Binder 驱动统计信息
adb shell cat /sys/kernel/debug/binder/stats

# 输出关键字段解读：
# BC_TRANSACTION: xxx       ← 发出的事务数
# BC_REPLY: xxx             ← 回复的事务数
# BR_TRANSACTION: xxx       ← 收到的事务数
# BR_REPLY: xxx             ← 收到的回复数
```

### 4.2 查看 Binder 事务

```bash
# 查看当前活跃的 Binder 事务
adb shell cat /sys/kernel/debug/binder/transactions

# 查看特定进程的 Binder 状态
adb shell cat /sys/kernel/debug/binder/proc/$(adb shell pidof com.example.binderdemo)
```

### 4.3 观察 Binder 线程

```bash
# 查看 BinderDemo Server 进程的线程
adb shell ps -T -p $(adb shell pidof com.example.binderdemo:remote)

# 预期输出中会看到 Binder 线程：
# PID   TID   NAME
# 12378 12378 nderdemo:remote   ← 主线程
# 12378 12380 Binder:12378_1    ← Binder 线程 1
# 12378 12381 Binder:12378_2    ← Binder 线程 2
```

### 4.4 监控 Binder 通信延迟

```bash
# 方法 1：通过 logcat 观察 BinderDemo 中自带的耗时日志
adb logcat -s "BinderDemo-Client:D" | grep "耗时"

# 方法 2：使用 systrace 抓取 Binder 相关 trace
python systrace.py -t 5 -o binder_trace.html binder_driver

# 方法 3：dumpsys 查看 Binder 相关信息
adb shell dumpsys activity | grep -i "binder"
```

### 4.5 模拟 Binder 线程耗尽

在 `CalculatorService.java` 的 `add()` 方法中添加延迟：

```java
@Override
public int add(int a, int b) throws RemoteException {
    Log.d(TAG, "add() 开始，线程: " + Thread.currentThread().getName());
    
    // 模拟耗时操作（模拟 Binder 线程被占用）
    try {
        Thread.sleep(5000);  // 阻塞 5 秒
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
    
    Log.d(TAG, "add() 结束");
    return a + b;
}
```

然后快速连续点击多次「加法」按钮，观察：
1. 客户端主线程是否阻塞（ANR 风险）
2. 服务端有多少 Binder 线程被创建
3. 线程名称的变化（Binder:xxxx_1, _2, _3...）

---

## 实验五：Binder 通信全链路追踪

### 5.1 验证进程隔离

```bash
# 运行 BinderDemo 后验证两个进程存在
adb shell ps -A | grep binderdemo

# 预期输出：
# u0_a123  12345  ...  com.example.binderdemo           ← Client
# u0_a123  12378  ...  com.example.binderdemo:remote    ← Server

# 查看两个进程的内存映射差异
adb shell cat /proc/12345/maps | grep binder
adb shell cat /proc/12378/maps | grep binder
```

### 5.2 数据流完整路径

```
用户操作                  调用链路                           进程
─────────── ──────────────────────────────── ─────────
点击「加法」 → performAdd()                      Client (PID: 12345)
            → mService.add(42, 18)              Client - 调用 Proxy
            → Proxy: Parcel 序列化参数            Client - 打包数据
            → BinderProxy.transact()            Client → Binder Driver
            ═══════════════════════════════════ 跨进程边界
            → Binder Driver 转发                 Kernel
            ═══════════════════════════════════ 跨进程边界
            → Stub.onTransact()                 Server (PID: 12378)
            → 反序列化 → add(42, 18) → return 60 Server - 执行逻辑
            → reply.writeInt(60)                Server → Binder Driver
            ═══════════════════════════════════ 返回
            → Proxy: reply.readInt() → 60       Client - 获取结果
            → tvResult.setText("60")            Client - 更新 UI
```

### 5.3 用 strace 跟踪系统调用（高级）

```bash
# 附加到 Client 进程，观察 ioctl 调用（Binder 通信底层）
adb shell strace -p 12345 -e ioctl -f 2>&1 | grep binder

# 你会看到类似输出：
# ioctl(7, BINDER_WRITE_READ, {write_size=96, ...}) = 0
# 这就是 Binder 通信在系统调用层面的体现
```

---

## ✅ 实操检查清单

完成以下所有项目，今天的实操目标就达成了：

- [ ] 阅读 servicemanager 的 main.cpp，理解启动流程
- [ ] 执行 `adb shell service list`，统计服务总数
- [ ] 成功运行 BinderDemo，完成跨进程加法计算
- [ ] 通过 Logcat 观察到 Client 和 Server 的 PID 不同
- [ ] 确认 IBinder 类型为 `android.os.BinderProxy`（跨进程标志）
- [ ] 执行 `adb shell ps -A | grep binderdemo` 看到两个进程
- [ ] 查看 `/sys/kernel/debug/binder/stats` 中的事务统计
- [ ] 观察到 Binder 线程名称格式（Binder:xxxx_N）
- [ ] （扩展）为 BinderDemo 添加乘法方法并验证

---

## 📝 实操笔记模板

完成实验后，记录以下信息：

```
=== Day 2 下午实操笔记 ===

1. 系统 Binder 服务总数：______ 个

2. BinderDemo 运行结果：
   - Client PID: ______
   - Server PID: ______
   - IBinder 类型: ______
   - 一次 Binder 调用耗时: ______ μs

3. Binder 线程观察：
   - Server 进程的 Binder 线程数: ______
   - 线程名称: ______

4. 关键理解：
   - servicemanager handle 0 的意义：
   - Stub.asInterface() 的作用：
   - android:process=":remote" 的效果：

5. 遇到的问题 & 解决方法：
   - 
```

---

## 💡 C++ 开发者深入思考

作为 C++ 开发者，你可以额外关注：

1. **servicemanager 的 C++ 实现**比 Java 层服务更底层，它直接与 Binder Driver 交互
2. **sp<> / wp<>** 是 Android 的智能指针系统（`system/core/libutils/`），设计比 std::shared_ptr 更早
3. **Parcel** 的序列化效率设计：为什么不用 protobuf？（答：Binder 需要传输 IBinder 对象引用，protobuf 做不到）
4. **Native 层 Binder**：你可以尝试阅读 `frameworks/native/libs/binder/` 中的 C++ Binder 实现

---

## 📖 延伸：Native 层 Binder 服务编写（预览 Day 4 内容）

Day 4 会深入 Native 层，届时你将学习如何用 C++ 编写 Binder 服务：

```cpp
// Native Binder 服务示例（预览）
class BnMyService : public BnInterface<IMyService> {
    status_t onTransact(uint32_t code, const Parcel& data,
                        Parcel* reply, uint32_t flags) override {
        switch (code) {
            case TRANSACTION_add:
                int a = data.readInt32();
                int b = data.readInt32();
                reply->writeInt32(add(a, b));
                return NO_ERROR;
        }
        return BBinder::onTransact(code, data, reply, flags);
    }
};
```

这与你在 C++ 中使用 RPC 框架的经验非常类似！
