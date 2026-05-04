package com.example.studydemo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BinderDemo-Client";

    private ICalculatorService mService;
    private boolean mBound = false;

    private EditText etNumA, etNumB;
    private TextView tvResult, tvProcessInfo;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "服务已连接!");
            Log.d(TAG, "IBinder 类型: " + service.getClass().getName());
            mService = ICalculatorService.Stub.asInterface(service);
            mBound = true;
            runOnUiThread(() -> {
                tvResult.setText("服务已连接，可以开始计算！");
                Toast.makeText(MainActivity.this, "远程服务已连接", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "服务已断开!");
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etNumA = findViewById(R.id.et_num_a);
        etNumB = findViewById(R.id.et_num_b);
        tvResult = findViewById(R.id.tv_result);
        tvProcessInfo = findViewById(R.id.tv_process_info);

        Button btnBind = findViewById(R.id.btn_bind);
        Button btnUnbind = findViewById(R.id.btn_unbind);
        Button btnAdd = findViewById(R.id.btn_add);
        Button btnSubtract = findViewById(R.id.btn_subtract);
        Button btnMultiply = findViewById(R.id.btn_multiply);
        Button btnProcessInfo = findViewById(R.id.btn_process_info);
        Button btnSimulateANR = findViewById(R.id.btn_simulate_anr);
        Button btnJNISystemInfo = findViewById(R.id.btn_jni_system_info);
        Button btnJNIHello = findViewById(R.id.btn_jni_hello);
        Button btnJNIArray = findViewById(R.id.btn_jni_array);

        tvProcessInfo.setText("Client PID: " + Process.myPid()
                + " | UID: " + Process.myUid()
                + " | Thread: " + Thread.currentThread().getName());

        btnBind.setOnClickListener(v -> bindRemoteService());
        btnUnbind.setOnClickListener(v -> unbindRemoteService());
        btnAdd.setOnClickListener(v -> performAdd());
        btnSubtract.setOnClickListener(v -> performSubtract());
        btnMultiply.setOnClickListener(v -> performMultiply());
        btnProcessInfo.setOnClickListener(v -> getServerInfo());
        btnSimulateANR.setOnClickListener(v -> performSimulateANR());
        btnJNISystemInfo.setOnClickListener(v -> getNativeSystemInfo());
        btnJNIHello.setOnClickListener(v -> sayHelloFromNative());
        btnJNIArray.setOnClickListener(v -> processNativeArray());

        Log.d(TAG, "Activity 创建 | PID: " + Process.myPid());
    }

    private void bindRemoteService() {
        Log.d(TAG, "正在绑定远程服务...");
        Intent intent = new Intent(this, CalculatorService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindRemoteService() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
            tvResult.setText("服务已断开");
            Log.d(TAG, "已解绑远程服务");
        }
    }

    private void performAdd() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            int a = Integer.parseInt(etNumA.getText().toString());
            int b = Integer.parseInt(etNumB.getText().toString());
            long startTime = System.nanoTime();
            int result = mService.add(a, b);
            long costTime = System.nanoTime() - startTime;
            tvResult.setText("结果: " + a + " + " + b + " = " + result
                    + "\n耗时: " + (costTime / 1000) + " μs（含 Binder 通信开销）");
            Log.d(TAG, "add() 跨进程调用成功: " + result + " | 耗时: " + costTime + " ns");
        } catch (RemoteException e) {
            tvResult.setText("远程调用失败: " + e.getMessage());
            Log.e(TAG, "RemoteException", e);
        } catch (NumberFormatException e) {
            tvResult.setText("请输入有效数字");
        }
    }

    private void performMultiply() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            int a = Integer.parseInt(etNumA.getText().toString());
            int b = Integer.parseInt(etNumB.getText().toString());
            long startTime = System.nanoTime();
            int result = mService.multiply(a, b);
            long costTime = System.nanoTime() - startTime;
            tvResult.setText("结果: " + a + " * " + b + " = " + result
                    + "\n耗时: " + (costTime / 1000) + " μs（含 Binder 通信开销）");
            Log.d(TAG, "multiply() 跨进程调用成功: " + result + " | 耗时: " + costTime + " ns");
        } catch (RemoteException e) {
            tvResult.setText("远程调用失败: " + e.getMessage());
            Log.e(TAG, "RemoteException", e);
        } catch (NumberFormatException e) {
            tvResult.setText("请输入有效数字");
        }
    }

    private void performSubtract() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            int a = Integer.parseInt(etNumA.getText().toString());
            int b = Integer.parseInt(etNumB.getText().toString());
            long startTime = System.nanoTime();
            int result = mService.subtract(a, b);
            long costTime = System.nanoTime() - startTime;
            tvResult.setText("结果: " + a + " - " + b + " = " + result
                    + "\n耗时: " + (costTime / 1000) + " μs（含 Binder 通信开销）");
            Log.d(TAG, "subtract() 跨进程调用成功: " + result);
        } catch (RemoteException e) {
            tvResult.setText("远程调用失败: " + e.getMessage());
        } catch (NumberFormatException e) {
            tvResult.setText("请输入有效数字");
        }
    }

    private void getServerInfo() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            String serverInfo = mService.getServerProcessInfo();
            String clientInfo = "Client PID: " + Process.myPid()
                    + "\nClient UID: " + Process.myUid()
                    + "\nClient Thread: " + Thread.currentThread().getName();
            tvResult.setText("=== 跨进程验证 ===\n\n"
                    + "【客户端】\n" + clientInfo
                    + "\n\n【服务端】\n" + serverInfo
                    + "\n\n→ PID 不同，说明是跨进程通信！");
            Log.d(TAG, "Server info:\n" + serverInfo);
        } catch (RemoteException e) {
            tvResult.setText("远程调用失败: " + e.getMessage());
        }
    }

    private void performSimulateANR() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            tvResult.setText("正在调用远程服务，将阻塞 10 秒...\n注意：可能会触发 ANR！");
            Log.w(TAG, "开始调用 simulateANR(10000)，客户端将等待...");
            long startTime = System.currentTimeMillis();
            mService.simulateANR(20000);
            long costTime = System.currentTimeMillis() - startTime;
            tvResult.setText("远程调用完成！\n耗时: " + costTime + " ms");
            Log.d(TAG, "simulateANR() 返回，耗时: " + costTime + " ms");
        } catch (RemoteException e) {
            tvResult.setText("RemoteException: " + e.getMessage());
            Log.e(TAG, "RemoteException", e);
        }
    }

    private void getNativeSystemInfo() {
        try {
            String info = NativeHelper.getSystemInfo();
            tvResult.setText("=== JNI 系统信息 ===\n\n" + info);
            Log.d(TAG, "getNativeSystemInfo() returned:\n" + info);
        } catch (Exception e) {
            tvResult.setText("JNI 调用失败: " + e.getMessage());
            Log.e(TAG, "getNativeSystemInfo() failed", e);
        }
    }

    private void sayHelloFromNative() {
        try {
            String name = etNumA.getText().toString().isEmpty() ? "Android" : etNumA.getText().toString();
            String result = NativeHelper.hello(name);
            tvResult.setText("=== JNI Hello ===\n\n" + result);
            Log.d(TAG, "hello() returned: " + result);
        } catch (Exception e) {
            tvResult.setText("JNI 调用失败: " + e.getMessage());
            Log.e(TAG, "hello() failed", e);
        }
    }

    private void processNativeArray() {
        try {
            int[] numbers = {10, 25, 33, 47, 52, 68, 71, 89, 95, 100};
            String result = NativeHelper.processIntArray(numbers);
            tvResult.setText("=== JNI 数组处理 ===\n\n" + result);
            Log.d(TAG, "processIntArray() returned: " + result);
        } catch (Exception e) {
            tvResult.setText("JNI 调用失败: " + e.getMessage());
            Log.e(TAG, "processIntArray() failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindRemoteService();
    }
}