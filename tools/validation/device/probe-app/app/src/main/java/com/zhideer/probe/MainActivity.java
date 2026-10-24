package com.zhideer.probe;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 知时鹿 #3 独立能力探针：只使用合成测试数据。
 * 应用内能力快照与用户显式触发的动作分开；所有结果同时写入
 * logcat（tag: ZhideerProbe）与界面文本，供截图与 adb logcat 采集。
 */
public class MainActivity extends Activity {

    static final String TAG = "ZhideerProbe";
    static final String CHANNEL_ID = "probe_exact";
    private static final int REQ_POST_NOTIFICATIONS = 41;

    private TextView logView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private PendingIntent exactPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "探针精确提醒",
                NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        Button btnSnapshot = button(root, "1. 能力快照（只读）");
        Button btnNotifyPerm = button(root, "2. 请求通知权限");
        Button btnNotifyNow = button(root, "3. 立即发一条测试通知");
        Button btnExact = button(root, "4. 设 2 分钟精确提醒");
        Button btnCancelExact = button(root, "5. 取消精确提醒");
        Button btnTts = button(root, "6. 中文 TTS 测试");
        Button btnRecognition = button(root, "7. 语音识别可用性");
        Button btnClock = button(root, "8. 交接系统闹钟（预填 +2 分钟）");
        Button btnShowAlarms = button(root, "9. 打开系统时钟列表");

        logView = new TextView(this);
        logView.setTextSize(13f);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        btnSnapshot.setOnClickListener(v -> snapshot());
        btnNotifyPerm.setOnClickListener(v -> requestNotificationPermission());
        btnNotifyNow.setOnClickListener(v -> postNotification("探针：手动触发的立即通知", "合成测试数据，非产品提醒"));
        btnExact.setOnClickListener(v -> scheduleExact());
        btnCancelExact.setOnClickListener(v -> cancelExact());
        btnTts.setOnClickListener(v -> ttsTest());
        btnRecognition.setOnClickListener(v -> recognitionSnapshot());
        btnClock.setOnClickListener(v -> handoffSystemClock());
        btnShowAlarms.setOnClickListener(v -> startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));

        log("探针启动。所有内容均为合成测试数据；应用层快照与真实投递分开记录。");
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private synchronized void log(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> logView.append(line + "\n"));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_POST_NOTIFICATIONS);
            log("已弹出发通知权限请求（拒绝后可用第 1 项观察 areNotificationsEnabled）");
        } else {
            log("API < 33 无需运行时通知权限");
        }
    }

    private void snapshot() {
        log("=== 能力快照开始（只读） ===");
        log("Build: " + Build.MANUFACTURER + " / " + Build.MODEL + " / " + Build.DEVICE);
        log("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), 补丁 " + Build.VERSION.SECURITY_PATCH);
        log("ColorOS 属性 oplusrom: " + systemProperty("ro.build.version.oplusrom"));
        log("时区: " + Calendar.getInstance().getTimeZone().getID());

        NotificationManager nm = getSystemService(NotificationManager.class);
        log("通知总开关 areNotificationsEnabled=" + nm.areNotificationsEnabled());
        StringBuilder chans = new StringBuilder("通知渠道: ");
        for (NotificationChannel c : nm.getNotificationChannels()) {
            chans.append("[").append(c.getId()).append(" importance=").append(c.getImportance()).append("] ");
        }
        log(chans.toString());

        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = getSystemService(AlarmManager.class);
            log("canScheduleExactAlarms=" + am.canScheduleExactAlarms());
        } else {
            log("canScheduleExactAlarms: API < 31，无该检查");
        }

        PackageManager pm = getPackageManager();
        log("SET_ALARM 处理器: " + handlerSummary(pm, new Intent(AlarmClock.ACTION_SET_ALARM)));
        log("SHOW_ALARMS 处理器: " + handlerSummary(pm, new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        String defaultTts = Settings.Secure.getString(getContentResolver(), "tts_default_engine");
        log("默认 TTS 引擎设置: " + defaultTts);
        log("=== 能力快照结束 ===");
    }

    private String handlerSummary(PackageManager pm, Intent intent) {
        List<ResolveInfo> list = pm.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo ri : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ri.activityInfo.packageName).append("/").append(ri.activityInfo.name);
        }
        return list.isEmpty() ? "无处理器" : sb.toString();
    }

    private void recognitionSnapshot() {
        PackageManager pm = getPackageManager();
        List<ServiceInfo> services = pm.queryIntentServices(
                new Intent("android.speech.RecognitionService"), 0).stream()
                .map(ri -> ri.serviceInfo).collect(java.util.stream.Collectors.toList());
        boolean available = SpeechRecognizer.isRecognitionAvailable(this);
        log("RecognitionService 组件: " + (services.isEmpty() ? "无" : services.toString()));
        log("SpeechRecognizer.isRecognitionAvailable=" + available);
        if (services.isEmpty() || !available) {
            log("结论：缺少标准识别服务 → 按住说话需提供中文说明与键盘回退（D07），不得静默外发录音");
        }
    }

    private void postNotification(String title, String text) {
        postNotificationReal(title, text, 0L);
    }

    private void postNotificationReal(String title, String text, long scheduledAtMillis) {
        android.app.Notification.Builder nb = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text + (scheduledAtMillis > 0
                        ? " | 预定 " + scheduledAtMillis + " 实际 " + System.currentTimeMillis()
                        : ""))
                .setAutoCancel(true);
        try {
            getSystemService(NotificationManager.class).notify((int) System.currentTimeMillis(), nb.build());
            log("通知已投递: " + title + (scheduledAtMillis > 0
                    ? "（预定 " + scheduledAtMillis + "，实际 " + System.currentTimeMillis()
                    + "，偏差 " + (System.currentTimeMillis() - scheduledAtMillis) + "ms）" : ""));
        } catch (SecurityException se) {
            log("通知投递被拒绝（SecurityException）: " + se.getMessage());
        }
    }

    private void scheduleExact() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            log("无法精确调度（canScheduleExactAlarms=false）。按 D02：不悄悄降级，先引导授权");
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        long triggerAt = System.currentTimeMillis() + 2 * 60 * 1000L;
        Intent intent = new Intent(this, ProbeAlarmReceiver.class)
                .putExtra("scheduled_at", triggerAt);
        exactPending = PendingIntent.getBroadcast(this, 9001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, exactPending);
        log("已设精确提醒: 预定 " + triggerAt + "（2 分钟后）；锁屏等待以核对 D01/D02");
        log("提示：先在系统设置拒绝通知重试一次（观察降级行为），再允许并核对偏差");
    }

    private void cancelExact() {
        if (exactPending != null) {
            getSystemService(AlarmManager.class).cancel(exactPending);
            log("已取消精确提醒");
        } else {
            log("没有已设置的精确提醒");
        }
    }

    private void ttsTest() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        log("TTS: 初始化中…");
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                log("TTS: 初始化失败 status=" + status + " → 缺引擎回退：中文说明+保留文字（D08）");
                return;
            }
            ttsReady = true;
            String engine = tts.getDefaultEngine();
            int zh = tts.isLanguageAvailable(new Locale("zh", "CHN"));
            int zhCn = tts.isLanguageAvailable(new Locale("zh", "CN"));
            log("TTS: 初始化成功 engine=" + engine
                    + " isLanguageAvailable(zh_CHN)=" + langName(zh)
                    + " (zh_CN)=" + langName(zhCn));
            if (zhCn >= TextToSpeech.LANG_AVAILABLE || zh >= TextToSpeech.LANG_AVAILABLE) {
                int r = tts.speak("知时鹿探针中文播报测试", TextToSpeech.QUEUE_FLUSH, null, "probe-utt-1");
                log("TTS: speak() 返回 " + r + "，请人工确认是否听到中文播报（D08）");
            } else {
                log("TTS: 缺少中文语音数据 → 说明并保留文字（D08），不标成功");
            }
        });
    }

    private String langName(int v) {
        switch (v) {
            case TextToSpeech.LANG_NOT_SUPPORTED: return "NOT_SUPPORTED";
            case TextToSpeech.LANG_MISSING_DATA: return "MISSING_DATA";
            case TextToSpeech.LANG_AVAILABLE: return "AVAILABLE";
            case TextToSpeech.LANG_COUNTRY_AVAILABLE: return "COUNTRY_AVAILABLE";
            case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE: return "COUNTRY_VAR_AVAILABLE";
            default: return String.valueOf(v);
        }
    }

    private void handoffSystemClock() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 2);
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                .putExtra(AlarmClock.EXTRA_MESSAGE, "知时鹿探针-合成测试")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        log("系统时钟交接：预填 " + cal.get(Calendar.HOUR_OF_DAY) + ":"
                + cal.get(Calendar.MINUTE) + "，是否保存由用户在时钟界面确认（D09，不虚构回执）");
        startActivity(i);
    }

    private String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null || v.toString().isEmpty() ? "(空)" : v.toString();
        } catch (Throwable t) {
            return "(读取失败)";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            log("通知权限请求结果: " + (granted ? "允许" : "拒绝") + "（拒绝时事项仍在、提醒标记未就绪，提供设置入口）");
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
