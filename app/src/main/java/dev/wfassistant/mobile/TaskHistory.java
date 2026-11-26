package dev.wfassistant.mobile;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class TaskHistory {
    private static final String STARTED_AT = "started_at";
    private static final String LOG = "log";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final Context context;
    private final SharedPreferences prefs;

    TaskHistory(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("background_task", Context.MODE_PRIVATE);
    }

    void begin(String details) {
        prefs.edit().putLong(STARTED_AT, System.currentTimeMillis()).putString(LOG, line(details)).apply();
    }

    void update(String text) {
        if (!text.equals(read())) prefs.edit().putString(LOG, text).apply();
    }

    void finish(String text, String reason) {
        prefs.edit().remove(STARTED_AT).putString(LOG, text + "\n" + line(reason)).apply();
    }

    String read() {
        return prefs.getString(LOG, "");
    }

    void recoverInterruptedRun() {
        long startedAt = prefs.getLong(STARTED_AT, 0);
        if (startedAt == 0) return;
        String reason = "上次后台任务异常中断，系统没有提供本应用的退出记录";
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8)) {
                if (exit.getTimestamp() < startedAt || !context.getPackageName().equals(exit.getProcessName())) continue;
                reason = "上次后台任务中断 · 应用退出原因：" + describeExit(exit.getReason());
                break;
            }
        } catch (RuntimeException error) {
            Log.w("WarframeSurvival", "Could not read application exit history", error);
        }
        finish(read(), reason);
    }

    static String describeExit(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "进程主动退出";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足";
            case ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "应用崩溃";
            case ApplicationExitInfo.REASON_ANR -> "应用无响应";
            case ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "应用被停止";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用超限";
            case ApplicationExitInfo.REASON_SIGNALED -> "进程收到终止信号";
            case ApplicationExitInfo.REASON_FREEZER -> "系统冻结进程";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用已更新";
            case ApplicationExitInfo.REASON_OTHER -> "系统终止进程";
            default -> "系统原因代码 " + reason;
        };
    }

    private static String line(String message) {
        return LocalDateTime.now().format(DATE_TIME) + "  " + message;
    }
}
