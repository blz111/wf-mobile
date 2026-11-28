package dev.wfassistant.mobile;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

final class TaskHistory {
    private static final String STARTED_AT = "started_at";
    private static final String LOG = "log";
    private static final String LOG_STARTED_AT = "log_started_at";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT);

    private final Context context;
    private final SharedPreferences prefs;

    TaskHistory(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("background_task", Context.MODE_PRIVATE);
    }

    void begin(String details) throws IOException {
        long now = System.currentTimeMillis();
        String previous = read();
        if (!previous.isEmpty()) {
            archive(previous, prefs.getLong(LOG_STARTED_AT, prefs.getLong(STARTED_AT, now)));
        }
        prefs.edit().putLong(STARTED_AT, now).putLong(LOG_STARTED_AT, now).putString(LOG, line(details)).apply();
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

    File[] archives() {
        File[] files = archiveDirectory().listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }

    String readArchive(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private File archiveDirectory() {
        return new File(context.getFilesDir(), "task-logs");
    }

    private void archive(String previous, long time) throws IOException {
        File directory = archiveDirectory();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建历史日志目录");
        }
        String stamp = FILE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
        File destination = new File(directory, stamp + ".log");
        for (int suffix = 2; destination.exists(); suffix++) {
            if (previous.equals(readArchive(destination))) return;
            destination = new File(directory, stamp + "_" + suffix + ".log");
        }
        AtomicFile archive = new AtomicFile(destination);
        FileOutputStream output = archive.startWrite();
        try {
            output.write(previous.getBytes(StandardCharsets.UTF_8));
            archive.finishWrite(output);
        } catch (IOException error) {
            archive.failWrite(output);
            throw error;
        }
        if (!destination.isFile()) throw new IOException("无法保存历史日志");
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
