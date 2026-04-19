// ICalculatorService.aidl
// 这是一个 AIDL 接口文件
// Android 编译器会自动生成对应的 Java 代码（Stub + Proxy）
package com.example.binderdemo;

interface ICalculatorService {
    // 加法运算（跨进程调用）
    int add(int a, int b);

    // 减法运算
    int subtract(int a, int b);

    // 获取服务端进程信息
    String getServerProcessInfo();
}
