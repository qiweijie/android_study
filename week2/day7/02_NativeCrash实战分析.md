# Day 7 下午：Native Crash 实战分析

## 🎯 实操目标
- 掌握 addr2line / llvm-symbolizer 的符号化流程
- 分析 3 个典型 Tombstone：空指针、use-after-free、栈溢出
- 构造 Native Crash 并完成完整分析链路
- 了解 coredump + GDB 离线分析方法

---

## 实验一：符号化工具实操

### 1.1 addr2line 使用

```bash
# 基本用法
# addr2line -C -f -e <带符号的.so> <地址>

# 示例：从 Tombstone 中提取地址
# backtrace:
#   #00 pc 00012a48  /data/app/.../libmylib.so
#   #01 pc 00012abc  /data/app/.../libmylib.so

# 符号化
$NDK/toolchains/llvm/prebuilt/*/bin/llvm-addr2line \
    -C -f -e ./obj/local/arm64-v8a/libmylib.so \
    0x00012a48 0x00012abc

# 输出：
# MyClass::processData(int*, int)
# /path/to/src/my_class.cpp:42
# MyClass::run()
# /path/to/src/my_class.cpp:28
```

### 1.2 ndk-stack 自动化

```bash
# 直接处理整个 Tombstone
adb pull /data/tombstones/tombstone_00 .
ndk-stack -sym ./symbols/ -dump tombstone_00

# 或从 logcat 实时符号化
adb logcat -b crash | ndk-stack -sym ./symbols/
```

---

## 实验二：3 个典型案例分析

### 案例 1：空指针解引用 (SIGSEGV, SEGV_MAPERR)

```
构造代码：
void processData(Data* data) {
    data->value = 42;  // data == nullptr → SIGSEGV
}

Tombstone 关键信息：
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8
    x0  0000000000000000  ← x0 = nullptr (对象指针)
backtrace:
    #00 pc 00012a48  libmylib.so (processData+16)

分析：
→ fault addr 0x8 = nullptr + 偏移 8（结构体成员 offset）
→ x0 = 0 = 空指针
→ 根因：传入了 nullptr 的 Data 指针
```

### 案例 2：Use-After-Free (SIGSEGV/SIGABRT)

```
构造代码：
Data* data = new Data();
delete data;
data->value = 42;  // Use-after-free!

Tombstone 关键信息：
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x7f80012340
  或
signal 6 (SIGABRT)
Abort message: 'Use-after-free on address 0x7f80012340'  (ASan)

分析：
→ SEGV_ACCERR = 地址已映射但权限不足（被 free 后标记为不可访问）
→ 如果有 ASan 会直接给出 use-after-free 信息
→ 无 ASan 时需要看 fault addr 是否在已知的 heap 区域
```

### 案例 3：栈溢出 (SIGSEGV, stack overflow)

```
构造代码：
void recursive(int n) {
    char buf[4096];
    recursive(n + 1);  // 无限递归
}

Tombstone 关键信息：
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x7f800ff000
Cause: stack pointer is in a mapped stack guard
backtrace:
    #00 pc 00012a48  libmylib.so (recursive+24)
    #01 pc 00012a48  libmylib.so (recursive+24)  ← 重复！
    #02 pc 00012a48  libmylib.so (recursive+24)
    ... (重复数百次)

分析：
→ Cause: "stack pointer is in a mapped stack guard" = 栈溢出
→ 调用栈中同一个函数重复出现 = 无限递归
→ fault addr 在栈守护页（guard page）范围内
```

---

## 实验三：构造 Native Crash

### 3.1 使用 Day 4 的 JNI Demo

```java
// 调用之前写的 triggerCrash 方法
NativeHelper.triggerCrash(0);  // 空指针
NativeHelper.triggerCrash(2);  // abort
```

### 3.2 收集和分析

```bash
# Step 1: 触发 Crash
# Step 2: 收集 Tombstone
adb shell ls -la /data/tombstones/
adb pull /data/tombstones/tombstone_00 .

# Step 3: 查看 crash logcat
adb logcat -b crash -d > crash.log

# Step 4: 符号化
ndk-stack -sym ./app/build/intermediates/cmake/debug/obj/arm64-v8a/ \
    -dump tombstone_00

# Step 5: 完整分析
# → 信号类型? → fault addr? → 寄存器? → 调用栈? → 根因?
```

---

## 实验四：GDB / coredump 分析（高级）

### 4.1 启用 coredump

```bash
# Android 默认不生成 coredump，需要手动启用
adb shell ulimit -c unlimited
adb shell setprop debug.generate-debug-info true

# coredump 路径
# /data/core/ 或 /data/tombstones/
```

### 4.2 GDB 加载 coredump

```bash
# 使用 NDK 中的 GDB
$NDK/prebuilt/linux-x86_64/bin/gdb \
    -ex "set solib-search-path ./symbols/system/lib64:./symbols/vendor/lib64" \
    -ex "file ./symbols/system/bin/app_process64" \
    -ex "core-file ./core.12345"

# GDB 命令
(gdb) bt            # 查看调用栈
(gdb) info threads   # 所有线程
(gdb) thread 1       # 切换线程
(gdb) frame 3        # 切换栈帧
(gdb) info locals    # 查看局部变量
(gdb) print *data    # 打印变量
(gdb) x/16x $sp      # 查看栈内存
```

---

## ✅ 实操检查清单

- [ ] 使用 addr2line 将地址还原为函数名和行号
- [ ] 使用 ndk-stack 自动化处理了一个 Tombstone
- [ ] 分析了空指针、use-after-free、栈溢出 3 种案例
- [ ] 构造了 Native Crash 并完成从触发到分析的全流程
- [ ] 理解 memory map 段在分析中的作用

---

## 📝 实操笔记模板

```
=== Day 7 下午实操笔记 ===

案例分析记录：

案例 1 (空指针):
  信号: SIGSEGV / SEGV_MAPERR
  fault addr: 0x______
  崩溃函数: ______ (line ______)
  根因: ______

案例 2 (use-after-free):
  信号: ______
  检测方式: ASan / 手动分析
  根因: ______

案例 3 (栈溢出):
  特征: 调用栈中 ______ 重复出现
  Cause: ______

自己构造的 Crash:
  类型: ______
  收集的 Tombstone 编号: ______
  符号化后的函数名: ______
```
