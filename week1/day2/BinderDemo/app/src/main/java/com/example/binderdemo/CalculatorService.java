package com.example.binderdemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * 远程计算器服务
 *
 * 关键点：
 * 1. 在 AndroidManifest.xml 中配置 android:process=":remote"
 *    使其运行在独立进程中
 * 2. 实现 AIDL 接口的 Stub（服务端 Binder 对象）
 * 3. 在 onBind() 中返回 Binder 对象
 *
 * 这个 Service 运行在 ":remote" 进程中
 * 与 Activity 不是同一个进程
 * 它们之间的通信就是通过 Binder IPC 实现的
 */
public class CalculatorService extends Service {

    private static final String TAG = "BinderDemo-Service";

    /**
     * 这是 Binder 的服务端实现
     * Stub 是 AIDL 编译器自动生成的抽象类
     * 我们只需要实现接口中定义的方法
     */
    private final ICalculatorService.Stub mBinder = new ICalculatorService.Stub() {

        @Override
        public int add(int a, int b) throws RemoteException {
            Log.d(TAG, "add() 被调用: " + a + " + " + b
                    + " | 当前线程: " + Thread.currentThread().getName()
                    + " | PID: " + Process.myPid());

            // 模拟一些处理耗时（可选）
            // Thread.sleep(100);

            return a + b;
        }

        @Override
        public int subtract(int a, int b) throws RemoteException {
            Log.d(TAG, "subtract() 被调用: " + a + " - " + b
                    + " | 当前线程: " + Thread.currentThread().getName()
                    + " | PID: " + Process.myPid());
            return a - b;
        }

        @Override
        public String getServerProcessInfo() throws RemoteException {
            String info = "Server PID: " + Process.myPid()
                    + "\nServer UID: " + Process.myUid()
                    + "\nServer Thread: " + Thread.currentThread().getName()
                    + "\nServer TID: " + Process.myTid();
            Log.d(TAG, "getServerProcessInfo() 被调用\n" + info);
            return info;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service 创建 | PID: " + Process.myPid());
    }

    /**
     * 当 Client 调用 bindService() 时触发
     * 返回 Binder 对象给 Client
     *
     * 注意：
     * - 返回的 mBinder 在 Client 端会变成 Proxy 对象
     * - 因为 Client 和 Service 在不同进程
     * - Binder Driver 负责中间的数据转发
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind() | Client 正在绑定服务");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind() | Client 解绑服务");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service 销毁");
    }
}
