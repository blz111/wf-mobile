package dev.wfassistant.mobile;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundTaskService extends Service {
    private static final String START = "dev.wfassistant.mobile.START_BACKGROUND_TASK";
    private static final String STOP = "dev.wfassistant.mobile.STOP_BACKGROUND_TASK";
    private static final String CHANNEL = "running_task";
    private static final int NOTIFICATION_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::pollSession;
    private GameConnection connection;
    private TaskHistory history;
    private PowerManager.WakeLock wakeLock;
    private ISurvivalSession session;
    private String header, sessionLog = "";
    private boolean foreground, finishing;

    static void start(Context context, boolean relic, int limit, String macroJson) {
        GameConnection connection = AssistantApplication.connection(context);
        if (!connection.beginBackgroundTask()) return;
        try {
            context.startForegroundService(new Intent(context, BackgroundTaskService.class).setAction(START)
                    .putExtra("relic", relic).putExtra("limit", limit).putExtra("macro", macroJson));
        } catch (RuntimeException error) {
            TaskHistory history = new TaskHistory(context);
            String reason = "系统拒绝启动后台任务 · " + error.getMessage();
            history.begin(reason);
            history.finish(history.read(), "任务未启动");
            connection.endBackgroundTask();
            Toast.makeText(context, "后台任务启动失败，详情见日志", Toast.LENGTH_LONG).show();
            Log.w("WarframeSurvival", reason, error);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        connection = AssistantApplication.connection(this);
        history = new TaskHistory(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            if (session != null && !finishing) {
                ISurvivalSession active = session;
                worker.execute(() -> stopSession(active));
            } else if (session == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(action) || !connection.hasBackgroundTask()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (header != null) return START_NOT_STICKY;

        PowerManager power = getSystemService(PowerManager.class);
        boolean exempt = power.isIgnoringBatteryOptimizations(getPackageName());
        boolean restricted = getSystemService(ActivityManager.class).isBackgroundRestricted();
        history.begin("请求启动后台任务 · 系统电池优化" + (exempt ? "已豁免" : "仍开启")
                + " · Android 后台限制" + (restricted ? "已启用" : "未启用"));
        header = history.read();
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            foreground = true;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WarframeSurvival:RunningTask");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (RuntimeException error) {
            Log.w("WarframeSurvival", "Could not protect background task", error);
            Toast.makeText(this, "无法保护后台任务，详情见日志", Toast.LENGTH_LONG).show();
            finish("后台保护启动失败 · " + error);
            return START_NOT_STICKY;
        }
        session = connection.session();
        if (session == null) {
            finish("Shizuku 用户服务未连接，任务未启动");
            return START_NOT_STICKY;
        }

        ISurvivalSession active = session;
        boolean relic = intent.getBooleanExtra("relic", false);
        int limit = intent.getIntExtra("limit", 0);
        String macro = intent.getStringExtra("macro");
        worker.execute(() -> {
            try {
                boolean started = active.startSurvival(relic, limit, macro);
                readSession(active, started);
            } catch (RemoteException | RuntimeException error) {
                connectionLost(error);
            }
        });
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "后台任务", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BackgroundTaskService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_task_notification).setContentTitle("Warframe 生存任务")
                .setContentText("后台运行中 · 可以锁屏").setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder(null, "停止任务", stop).build()).build();
    }

    private void pollSession() {
        ISurvivalSession active = session;
        if (finishing || active == null) return;
        if (active != connection.session()) {
            finish("Shizuku 连接中断，后台任务已结束");
            return;
        }
        worker.execute(() -> {
            try {
                readSession(active, true);
            } catch (RemoteException | RuntimeException error) {
                connectionLost(error);
            }
        });
    }

    private void readSession(ISurvivalSession active, boolean started) throws RemoteException {
        int phase = active.phase();
        String status = active.status();
        String log = active.taskLog();
        main.post(() -> {
            if (finishing) return;
            sessionLog = log;
            if (!started) finish("任务未启动 · " + status);
            else if (phase != VirtualGameSession.RUNNING) finish("后台任务结束 · " + status);
            else {
                history.update(logText());
                main.postDelayed(poll, 1_000);
            }
        });
    }

    private void stopSession(ISurvivalSession active) {
        try {
            active.stopSurvival();
        } catch (RemoteException | RuntimeException error) {
            connectionLost(error);
        }
    }

    private void connectionLost(Exception error) {
        Log.w("WarframeSurvival", "Background task lost its Shizuku connection", error);
        main.post(() -> finish("Shizuku 用户服务通信失败 · " + error.getClass().getSimpleName()));
    }

    private String logText() {
        return sessionLog.isEmpty() ? header : header + "\n" + sessionLog;
    }

    private void finish(String reason) {
        if (finishing) return;
        finishing = true;
        if (header != null) history.finish(logText(), reason);
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (!finishing && header != null) {
            finishing = true;
            history.finish(logText(), "后台服务被停止");
            ISurvivalSession active = session;
            if (active != null) worker.execute(() -> stopSession(active));
        }
        worker.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        if (header != null) connection.endBackgroundTask();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
