package com.example.studydemo;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * 远程计算器服务
 */
public class CalculatorService extends Service {

    private static final String TAG = "BinderDemo-Service";

    private final ICalculatorService.Stub mBinder = new ICalculatorService.Stub() {

        @Override
        public int add(int a, int b) throws RemoteException {
            Log.d(TAG, "add() 被调用: " + a + " + " + b
                    + " | 当前线程: " + Thread.currentThread().getName()
                    + " | PID: " + Process.myPid());
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
        public int multiply(int a, int b) throws RemoteException {
            Log.d(TAG, "multiply() 被调用: " + a + " * " + b
                    + " | 当前线程: " + Thread.currentThread().getName()
                    + " | PID: " + Process.myPid());
            return a * b;
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

        @Override
        public void simulateANR(int blockTimeMs) throws RemoteException {
            Log.w(TAG, "simulateANR() 被调用，即将阻塞 " + blockTimeMs + " 毫秒...");
            try {
                Thread.sleep(blockTimeMs);
            } catch (InterruptedException e) {
                Log.e(TAG, "simulateANR() 被中断", e);
            }
            Log.d(TAG, "simulateANR() 执行完成");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service 创建 | PID: " + Process.myPid());
    }

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