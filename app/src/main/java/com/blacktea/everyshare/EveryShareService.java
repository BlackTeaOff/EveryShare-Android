package com.blacktea.everyshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import com.blacktea.everyshare.core.AppLogger;

public class EveryShareService extends Service {
    private static final String CHANNEL_ID = "EveryShare_Background_Channel";
    private static final int NOTIFICATION_ID = 1024;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLogger.info("[Service] 启动前台守护服务...");

        // 构建一个精美且低打扰的通知栏消息
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("EveryShare")
                .setContentText("正在后台服务以保持远程连接...")
                .setSmallIcon(R.drawable.avatar) // 直接复用你已有的 avatar 图标
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低打扰，不发出声音
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true) // 防止用户手动划掉
                .build();

        // 💡 适配 Android 10 (Q) 及以上版本的数据同步前台服务类型 [1]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_NOT_STICKY; // 如果服务被意外杀死，不需要自动重启
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        AppLogger.info("[Service] 前台守护服务已销毁");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "EveryShare 后台传输服务",
                    NotificationManager.IMPORTANCE_LOW // 低重要度，避免弹窗打扰用户
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}