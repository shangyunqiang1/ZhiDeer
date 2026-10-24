package com.zhideer.probe;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 精确提醒到达时投递合成测试通知。
 * 通知文本同时携带预定时间与实际投递时间，供 D02 准时性核对。
 * 真实投递证据以用户可见通知 + logcat 为准，与提交调度区分。
 */
public class ProbeAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long actual = System.currentTimeMillis();
        long scheduled = intent.getLongExtra("scheduled_at", 0L);
        String text = "知时鹿探针：精确提醒到达（合成测试数据）"
                + (scheduled > 0 ? " | 预定 " + scheduled + " 实际 " + actual
                + " 偏差 " + (actual - scheduled) + "ms" : "");
        Log.i(MainActivity.TAG, "AlarmReceiver fire: " + text);
        Notification n = new Notification.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("知时鹿探针精确提醒")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        try {
            context.getSystemService(NotificationManager.class).notify(9002, n);
            Log.i(MainActivity.TAG, "AlarmReceiver: 通知已投递 notify(9002)");
        } catch (SecurityException se) {
            Log.w(MainActivity.TAG, "AlarmReceiver: 通知被拒绝 " + se.getMessage());
        }
    }
}
