package com.example.binderdemo;

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

/**
 * Binder IPC 演示 - 客户端 Activity
 *
 * 演示内容：
 * 1. 绑定远程 Service（运行在另一个进程）
 * 2. 通过 AIDL 接口发起跨进程调用
 * 3. 观察 Client/Server 的 PID 不同（证明是跨进程通信）
 * 4. 体验 Binder 的"本地调用"体验（实际是跨进程）
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BinderDemo-Client";

    // AIDL 接口引用（实际是 Proxy 对象，因为跨进程）
    private ICalculatorService mService;
    private boolean mBound = false;

    private EditText etNumA, etNumB;
    private TextView tvResult, tvProcessInfo;

    /**
     * ServiceConnection - 服务连接回调
     *
     * 当绑定服务成功后，系统会回调 onServiceConnected
     * 传入的 IBinder 对象：
     *   - 同一进程时：直接是 Stub 对象
     *   - 跨进程时：是 BinderProxy 对象
     *
     * Stub.asInterface() 会判断是否跨进程：
     *   - 同进程：直接返回 Stub 自身
     *   - 跨进程：包装成 Proxy 返回
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "服务已连接!");
            Log.d(TAG, "IBinder 类型: " + service.getClass().getName());
            // 如果是跨进程，会输出: android.os.BinderProxy
            // 如果是同进程，会输出: ICalculatorService$Stub

            // 将 IBinder 转换为 AIDL 接口
            mService = ICalculatorService.Stub.asInterface(service);
            mBound = true;

            // 更新 UI
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

        // 初始化 UI
        etNumA = findViewById(R.id.et_num_a);
        etNumB = findViewById(R.id.et_num_b);
        tvResult = findViewById(R.id.tv_result);
        tvProcessInfo = findViewById(R.id.tv_process_info);

        Button btnBind = findViewById(R.id.btn_bind);
        Button btnUnbind = findViewById(R.id.btn_unbind);
        Button btnAdd = findViewById(R.id.btn_add);
        Button btnSubtract = findViewById(R.id.btn_subtract);
        Button btnProcessInfo = findViewById(R.id.btn_process_info);

        // 显示客户端进程信息
        tvProcessInfo.setText("Client PID: " + Process.myPid()
                + " | UID: " + Process.myUid()
                + " | Thread: " + Thread.currentThread().getName());

        // 绑定服务
        btnBind.setOnClickListener(v -> bindRemoteService());

        // 解绑服务
        btnUnbind.setOnClickListener(v -> unbindRemoteService());

        // 加法（跨进程调用）
        btnAdd.setOnClickListener(v -> performAdd());

        // 减法（跨进程调用）
        btnSubtract.setOnClickListener(v -> performSubtract());

        // 获取服务端进程信息（验证跨进程）
        btnProcessInfo.setOnClickListener(v -> getServerInfo());

        Log.d(TAG, "Activity 创建 | PID: " + Process.myPid());
    }

    /**
     * 绑定远程服务
     * 服务运行在 :remote 进程中
     */
    private void bindRemoteService() {
        Log.d(TAG, "正在绑定远程服务...");
        Intent intent = new Intent(this, CalculatorService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * 解绑远程服务
     */
    private void unbindRemoteService() {
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
            tvResult.setText("服务已断开");
            Log.d(TAG, "已解绑远程服务");
        }
    }

    /**
     * 执行加法 - 跨进程调用
     *
     * 看起来像本地方法调用: mService.add(a, b)
     * 实际底层经历了：
     *   1. Proxy.add() -> 将参数序列化到 Parcel
     *   2. BinderProxy.transact() -> 通过 Binder Driver 发送到服务端
     *   3. Stub.onTransact() -> 反序列化参数
     *   4. 调用 add() 实现 -> 计算结果
     *   5. 结果写入 reply Parcel -> 返回给客户端
     */
    private void performAdd() {
        if (!mBound) {
            tvResult.setText("请先绑定服务！");
            return;
        }
        try {
            int a = Integer.parseInt(etNumA.getText().toString());
            int b = Integer.parseInt(etNumB.getText().toString());

            long startTime = System.nanoTime();
            // 这一行就是跨进程调用！
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

    /**
     * 执行减法 - 跨进程调用
     */
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

    /**
     * 获取服务端进程信息
     * 对比 Client PID 和 Server PID，验证确实是跨进程通信
     */
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindRemoteService();
    }
}
