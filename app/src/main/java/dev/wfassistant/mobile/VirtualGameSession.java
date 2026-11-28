package dev.wfassistant.mobile;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.json.JSONObject;

import dev.wfassistant.mobile.GameTaskScheduler.TapResult;

public final class VirtualGameSession extends ISurvivalSession.Stub {
    static final int SHELL_UID = 2000;
    static final int CLOSED = 0, OPENING = 1, READY = 2, RUNNING = 3;
    static final int WIDTH = 1280, HEIGHT = 720;
    private static final int MANUAL_TOUCH_SETTLE_MS = 250;
    private static final int GAME_TASK_WATCH_MS = 1_500;
    private static final int INPUT_RELEASE_RETRIES = 3;
    private static final int INPUT_RELEASE_RETRY_MS = 200;
    private static final String INPUT_RELEASE_PENDING = " · 宏输入释放待重试";
    private static final int DISPLAY_ALWAYS_UNLOCKED = 1 << 9;
    private static final String GAME = "com.digitalextremes.warframemobile";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | (1 << 6)   // SUPPORTS_TOUCH
            | (1 << 8)   // DESTROY_CONTENT_ON_REMOVAL
            | (1 << 10)  // TRUSTED
            | (1 << 14)  // OWN_FOCUS
            | (1 << 16); // STEAL_TOP_FOCUS_DISABLED
    private static final int NON_STEALING_FOCUS = (1 << 11) | (1 << 12);

    private final Context shellContext;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final ScheduledThreadPoolExecutor displayPowerWorker = new ScheduledThreadPoolExecutor(1, task ->
            new Thread(task, "wf-display-power"));
    private final AtomicInteger queuedManualTouches = new AtomicInteger();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private long logSequence;
    private volatile String message = "Shizuku 已连接";
    private volatile int phase = CLOSED;
    private VirtualDisplay display;
    private VirtualGameDisplay virtualGameDisplay;
    private volatile GpuPreview renderer;
    private Surface previewSurface;
    private int previewWidth, previewHeight;
    private int gameUid;
    private Set<ComponentName> loginWindows = Set.of();
    private final Map<String, Integer> loginWindowUids = new LinkedHashMap<>();
    private int gestureUid;
    private String gameUser;
    private int trackedTaskId = -1;
    private int recentsHandledTaskId = -1;
    private long lastTaskMove;
    private long gameStartedAt;
    private ScheduledFuture<?> pending;
    private ScheduledFuture<?> powerKeepAlive;
    private ScheduledFuture<?> gameTaskWatch;
    private GameTaskScheduler taskScheduler;
    private GameTaskScheduler releasingTasks;
    private long lastManualTouch;
    private long gestureStart;
    private int[] activeIds;
    private float[] activeXs, activeYs;
    private int maxTouches;
    private Object inputManager;
    private Method injectEvent, setDisplayId;

    public VirtualGameSession(Context context) {
        if (Process.myUid() != SHELL_UID) throw new SecurityException("需要 ADB shell 身份");
        shellContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return VirtualGameDisplay.SHELL_PACKAGE; }
            @Override public String getOpPackageName() { return VirtualGameDisplay.SHELL_PACKAGE; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(SHELL_UID).setPackageName(getPackageName()).build();
            }
        };
        worker.setRemoveOnCancelPolicy(true);
        displayPowerWorker.setRemoveOnCancelPolicy(true);
    }

    @Override public String status() { return message; }
    @Override public int phase() { return phase; }

    @Override public synchronized String taskLog() { return String.join("\n", log); }

    @Override public synchronized String[] taskLogSince(long after) {
        if (after >= logSequence || log.isEmpty()) return new String[0];
        long oldest = logSequence - log.size() + 1;
        long first = Math.max(oldest, after + 1);
        String[] batch = new String[(int) (logSequence - first + 2)];
        batch[0] = Long.toString(first);
        int index = 1;
        long sequence = oldest;
        for (String entry : log) {
            if (sequence++ >= first) batch[index++] = entry;
        }
        return batch;
    }

    private synchronized void logEvent(String message) {
        if (log.size() == 120) log.removeFirst();
        log.addLast(LocalTime.now().format(LOG_TIME) + "  " + message);
        logSequence++;
    }

    private synchronized void startTaskLog(String summary) {
        log.clear();
        logSequence = 0;
        logEvent(summary);
    }

    @Override public boolean isGameVibrationMuted() { return gameOpMuted("VIBRATE"); }
    @Override public boolean setGameVibrationMuted(boolean muted) { return setGameOpMuted("VIBRATE", muted); }
    @Override public boolean isGameAudioMuted() { return gameOpMuted("PLAY_AUDIO"); }
    @Override public boolean setGameAudioMuted(boolean muted) { return setGameOpMuted("PLAY_AUDIO", muted); }

    private static boolean gameOpMuted(String op) {
        try {
            return readGameOpMuted(currentUser(), op);
        } catch (Exception error) {
            throw new IllegalStateException("读取 Warframe " + op + " 设置失败", error);
        }
    }

    private boolean setGameOpMuted(String op, boolean muted) {
        try {
            String user = currentUser();
            command("/system/bin/cmd", "appops", "set", "--user", user, GAME, op,
                    muted ? "ignore" : "allow");
            if (readGameOpMuted(user, op) != muted) {
                throw new IOException("Android 未应用 " + op + " 设置");
            }
            logEvent((muted ? "已关闭游戏" : "已恢复游戏") + ("VIBRATE".equals(op) ? "震动" : "声音"));
            return muted;
        } catch (Exception error) {
            throw new IllegalStateException("修改 Warframe " + op + " 设置失败", error);
        }
    }

    private static boolean readGameOpMuted(String user, String op) throws Exception {
        String result = command("/system/bin/cmd", "appops", "get", "--user", user, GAME, op);
        var mode = Pattern.compile("(?m)^\\s*(?:" + op + ":|Default mode:)\\s*(\\w+)").matcher(result);
        if (!mode.find()) throw new IOException("系统未返回 Warframe " + op + " 状态");
        return switch (mode.group(1)) {
            case "ignore", "deny", "errored" -> true;
            case "allow", "default", "foreground" -> false;
            default -> throw new IOException("未知 " + op + " 状态 · " + mode.group(1));
        };
    }

    private static String currentUser() throws Exception {
        return command("/system/bin/am", "get-current-user").trim();
    }

    @Override public boolean hasFrame() {
        GpuPreview active = renderer;
        return active != null && active.hasFrame();
    }

    @Override public void setPreviewSurface(Surface surface, int width, int height) {
        worker.execute(() -> {
            Surface previous = previewSurface;
            previewSurface = surface;
            previewWidth = width;
            previewHeight = height;
            try {
                if (renderer != null) attachPreview();
            } catch (Exception error) {
                Log.w("WarframePreview", "Could not attach preview surface", error);
                report(error);
            } finally {
                if (previous != null) previous.release();
            }
        });
    }

    @Override public void launch() {
        worker.execute(() -> {
            if (phase != CLOSED) return;
            phase = OPENING;
            maxTouches = 0;
            message = "正在启动 Warframe…";
            try {
                gameUser = currentUser();
                loginWindowUids.clear();
                String component = findLine(command("/system/bin/cmd", "package", "resolve-activity",
                        "--brief", "--user", gameUser, "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", GAME),
                        GAME + "/", "当前用户未安装 Warframe");
                gameUid = packageUid(GAME);
                createDisplay(ComponentName.unflattenFromString(component));
                int id = display.getDisplay().getDisplayId();
                gameStartedAt = System.currentTimeMillis();
                String result = command("/system/bin/am", "start", "--display", String.valueOf(id),
                        "--user", gameUser, "-f", String.valueOf(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), "-a", "android.intent.action.MAIN", "-c",
                        "android.intent.category.LAUNCHER", "-n", component);
                if (result.contains("Error:") || result.contains("Exception")) throw new IOException(result.trim());
                waitForGame(id, SystemClock.uptimeMillis() + 15_000);
            } catch (Exception error) {
                release();
                report(error);
            }
        });
    }

    private void createDisplay(ComponentName launcher) throws Exception {
        if (launcher == null) throw new IOException("无法解析 Warframe 启动窗口");
        Set<ComponentName> allowed = new HashSet<>();
        allowed.add(launcher);
        allowed.add(new ComponentName(GAME, GAME + ".MainActivity"));
        PackageManager packages = shellContext.getPackageManager();
        loginWindows = LoginWindows.resolve(packages);
        allowed.addAll(loginWindows);
        ActivityInfo[] activities = packages
                .getPackageInfo(GAME, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES)).activities;
        if (activities != null) {
            for (ActivityInfo activity : activities) allowed.add(new ComponentName(GAME, activity.name));
        }

        renderer = new GpuPreview(WIDTH, HEIGHT);
        // On Android 17, let the system put this trusted display in its virtual device's power group.
        int flags = Build.VERSION.SDK_INT >= 37 ? DISPLAY_FLAGS : DISPLAY_FLAGS | OWN_DISPLAY_GROUP;
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("Warframe Assistant", WIDTH, HEIGHT, 280)
                .setFlags(flags).setSurface(renderer.inputSurface()).build();
        virtualGameDisplay = VirtualGameDisplay.open(shellContext, config, allowed);
        display = virtualGameDisplay.display();
        if (display.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY
                || (display.getDisplay().getFlags() & NON_STEALING_FOCUS) != NON_STEALING_FOCUS) {
            throw new IOException("系统未创建具备独立焦点的虚拟屏");
        }
        if ((display.getDisplay().getFlags() & DISPLAY_ALWAYS_UNLOCKED) == 0) {
            logEvent("系统未授予虚拟屏始终解锁权限，已使用普通锁屏策略");
        }
        logEvent("虚拟屏已启用窗口白名单");
        attachPreview();
        startDisplayKeepAlive(display.getDisplay());
    }

    private void startDisplayKeepAlive(Display target) {
        try {
            int id = target.getDisplayId();
            VirtualDisplayPower power = new VirtualDisplayPower(id);
            Log.i("WarframePower", "Display " + id + " keep-alive enabled, alwaysUnlocked="
                    + ((target.getFlags() & DISPLAY_ALWAYS_UNLOCKED) != 0));
            powerKeepAlive = displayPowerWorker.scheduleWithFixedDelay(new Runnable() {
                private boolean failed;

                @Override public void run() {
                    try {
                        if (!target.isValid()) return;
                        power.keepAwake();
                        if (failed) logEvent("虚拟屏保活已恢复");
                        failed = false;
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        if (!failed && target.isValid()) {
                            Log.w("WarframePower", "Could not keep virtual display awake", error);
                            logEvent("虚拟屏保活失败 · " + (error.getCause() == null ? error : error.getCause()));
                        }
                        failed = true;
                    }
                }
            }, 0, 4, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WarframePower", "Display keep-alive unavailable", error);
            logEvent("无法启用虚拟屏保活 · " + error.getMessage());
        }
    }

    private void attachPreview() throws Exception {
        Surface target = previewSurface != null && previewSurface.isValid()
                && previewWidth > 0 && previewHeight > 0 ? previewSurface : null;
        renderer.attach(target, previewWidth, previewHeight);
    }

    private void waitForGame(int id, long deadline) {
        pending = null;
        try {
            Object task = findGameTask(id, false);
            if (previewActivity(task) != null) {
                trackGameTask(task, id);
                phase = READY;
                message = "Warframe 已在虚拟屏打开";
                logEvent(message);
                gameTaskWatch = worker.scheduleWithFixedDelay(() -> watchGameTask(id),
                        GAME_TASK_WATCH_MS, GAME_TASK_WATCH_MS, TimeUnit.MILLISECONDS);
            } else if (SystemClock.uptimeMillis() < deadline) {
                restoreMovedTask(id);
                pending = worker.schedule(() -> waitForGame(id, deadline), 600, TimeUnit.MILLISECONDS);
            } else {
                throw new IOException("等待 Warframe 出现在虚拟屏超时");
            }
        } catch (Exception error) {
            release();
            report(error);
        }
    }

    private void watchGameTask(int id) {
        try {
            Object task = findGameTask(id, false);
            if (task == null) restoreMovedTask(id);
            else trackGameTask(task, id);
            if (phase == READY && releasingTasks != null) {
                ComponentName top = previewActivity(task);
                if (top != null && GAME.equals(top.getPackageName()) && tryReleaseTaskInputs() == null) {
                    if (message.endsWith(INPUT_RELEASE_PENDING)) {
                        message = message.substring(0, message.length() - INPUT_RELEASE_PENDING.length());
                    }
                    logEvent("已完成宏输入释放");
                }
            }
        } catch (Exception error) {
            Log.w("WarframeTask", "Could not verify Warframe task", error);
        }
    }

    private void trackGameTask(Object task, int id) throws Exception {
        int taskId = task.getClass().getField("taskId").getInt(task);
        trackedTaskId = taskId;
        if (recentsHandledTaskId == taskId) return;
        recentsHandledTaskId = taskId;
        Intent base = (Intent) task.getClass().getField("baseIntent").get(task);
        Log.i("WarframeTask", "Warframe task " + taskId + " on display " + id
                + ", excludeFromRecents=" + (base != null && (base.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0));
        if (Build.VERSION.SDK_INT >= 37) {
            try {
                Object token = task.getClass().getField("token").get(task);
                Class<?> transactionType = Class.forName("android.window.WindowContainerTransaction");
                Object transaction = transactionType.getConstructor().newInstance();
                transactionType.getMethod("setTaskForceExcludedFromRecents",
                        Class.forName("android.window.WindowContainerToken"), boolean.class)
                        .invoke(transaction, token, true);
                Object organizer = taskManagerType().getMethod("getWindowOrganizerController").invoke(taskManager());
                Class.forName("android.window.IWindowOrganizerController")
                        .getMethod("applyTransaction", transactionType).invoke(organizer, transaction);
            } catch (ReflectiveOperationException error) {
                Log.w("WarframeTask", "Could not apply task-level Recents exclusion", error);
            }
        }
    }

    private boolean restoreMovedTask(int id) throws Exception {
        Object task = findGameTask(-1, true);
        if (task == null || task.getClass().getField("displayId").getInt(task) == id) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastTaskMove < GAME_TASK_WATCH_MS) return true;
        lastTaskMove = now;
        int taskId = task.getClass().getField("taskId").getInt(task);
        taskManagerType().getMethod("moveRootTaskToDisplay", int.class, int.class)
                .invoke(taskManager(), taskId, id);
        trackedTaskId = taskId;
        Log.i("WarframeTask", "Moved Warframe task " + taskId + " back to display " + id);
        return true;
    }

    private Object findGameTask(int displayId, boolean trackedOnly) throws Exception {
        List<?> tasks = (List<?>) (displayId < 0
                ? taskManagerType().getMethod("getAllRootTaskInfos").invoke(taskManager())
                : taskManagerType().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(taskManager(), displayId));
        Object candidate = null;
        boolean multipleMatches = false;
        int user = Integer.parseInt(gameUser);
        for (Object task : tasks) {
            Class<?> type = task.getClass();
            ComponentName base = (ComponentName) type.getField("baseActivity").get(task);
            if (base == null || !GAME.equals(base.getPackageName())
                    || type.getField("userId").getInt(task) != user) continue;
            int taskId = type.getField("taskId").getInt(task);
            if (taskId == trackedTaskId) return task;
            if (trackedOnly && trackedTaskId >= 0) continue;
            if (candidate == null) candidate = task;
            else multipleMatches = true;
        }
        return multipleMatches ? null : candidate;
    }

    private static Class<?> taskManagerType() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object taskManager() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    private Bitmap copyFrame() throws Exception {
        GpuPreview active = renderer;
        return active == null ? null : active.screenshot();
    }

    @Override public boolean saveScreenshot(ParcelFileDescriptor output) {
        if (output == null) return false;
        try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
            Bitmap screenshot = copyFrame();
            if (screenshot == null) return false;
            try {
                return screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } finally {
                screenshot.recycle();
            }
        } catch (Exception error) {
            Log.w("WarframePreview", "Could not save screenshot", error);
            return false;
        }
    }

    @Override public void touch(int action, int[] ids, float[] xs, float[] ys) {
        queuedManualTouches.incrementAndGet();
        worker.execute(() -> {
            try {
                if (phase != READY && phase != RUNNING) return;
                int masked = action & MotionEvent.ACTION_MASK;
                if (masked == MotionEvent.ACTION_CANCEL) {
                    cancelTouches();
                    return;
                }
                if (masked != MotionEvent.ACTION_DOWN && activeIds == null) return;
                if (masked == MotionEvent.ACTION_DOWN && taskScheduler != null) taskScheduler.pauseNormalTasks();
                int targetUid = masked == MotionEvent.ACTION_DOWN ? manualTargetUid() : gestureUid;
                sendTouch(action, ids, xs, ys, targetUid);
                if (masked == MotionEvent.ACTION_DOWN && phase == READY) message = targetUid == gameUid
                        ? "Warframe 已在虚拟屏打开" : "Google 选账号窗口 · 手动操作";
                if (ids.length > maxTouches) {
                    maxTouches = ids.length;
                    Log.i("WarframeSurvival", "Android accepted " + ids.length
                            + " simultaneous pointers on display " + display.getDisplay().getDisplayId());
                }
            } catch (Exception error) {
                cancelTouches();
                report(error);
            } finally {
                lastManualTouch = SystemClock.uptimeMillis();
                queuedManualTouches.decrementAndGet();
            }
        });
    }

    @Override public boolean startSurvival(boolean relicEnabled, int limit, String macroJson) {
        FutureTask<Boolean> request = new FutureTask<>(() -> {
            if (phase != READY) return false;
            if (limit < 0) {
                message = "开核桃数需要是非负整数";
                return false;
            }
            try {
                releaseTaskInputs();
                List<GameTaskScheduler.Task> tasks = new ArrayList<>();
                if (relicEnabled) tasks.add(new RelicTask(limit));
                if (macroJson != null && !macroJson.isBlank()) {
                    SkillMacro macro = SkillMacro.fromJson(new JSONObject(macroJson));
                    tasks.add(new MacroTask(macro, worker, new MacroInput(), this::logEvent, this::failTasks));
                }
                if (tasks.isEmpty()) return false;
                taskScheduler = new GameTaskScheduler(tasks);
                phase = RUNNING;
                List<String> progress = new ArrayList<>();
                for (GameTaskScheduler.Task task : tasks) progress.add(task.progress());
                startTaskLog("开始任务 · " + String.join(" · ", progress));
                pending = worker.schedule(this::scan, 0, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception error) {
                failTasks(error);
                return false;
            }
        });
        worker.execute(request);
        try {
            return request.get();
        } catch (InterruptedException error) {
            request.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException error) {
            Log.e("WarframeSurvival", "Could not start task", error);
            return false;
        }
    }

    private final class MacroInput implements MacroTask.Input {
        private final Map<Integer, Long> keysDown = new LinkedHashMap<>();
        private SkillMacro.Direction left, right;

        @Override public boolean isIdle() { return !manualTouchActive(); }

        @Override public boolean press(SkillMacro.Action action) throws Exception {
            if (phase != RUNNING || !isIdle()) return false;
            checkTarget();
            switch (action.kind()) {
                case GAMEPAD, GAMEPAD_DOWN -> {
                    if (!keysDown.containsKey(action.key())) {
                        long downTime = SystemClock.uptimeMillis();
                        sendGamepad(KeyEvent.ACTION_DOWN, action.key(), downTime);
                        keysDown.put(action.key(), downTime);
                    }
                }
                case JOYSTICK, JOYSTICK_MOVE -> moveStick(action.stick(), action.direction());
                case TAP, HOLD -> sendPoint(MotionEvent.ACTION_DOWN, new Point(action.x(), action.y()));
                default -> throw new IllegalArgumentException("此动作无需按下");
            }
            return true;
        }

        @Override public void release(SkillMacro.Action action) throws Exception {
            switch (action.kind()) {
                case GAMEPAD, GAMEPAD_UP -> releaseKey(action.key());
                case JOYSTICK, JOYSTICK_CENTER -> moveStick(action.stick(), null);
                case TAP, HOLD -> sendPoint(MotionEvent.ACTION_UP, new Point(action.x(), action.y()));
                default -> throw new IllegalArgumentException("此动作无需松开");
            }
        }

        private void releaseKey(int key) throws Exception {
            Long downTime = keysDown.get(key);
            if (downTime == null) return;
            sendGamepad(KeyEvent.ACTION_UP, key, downTime);
            keysDown.remove(key);
        }

        private void moveStick(SkillMacro.Stick stick, SkillMacro.Direction direction) throws Exception {
            SkillMacro.Direction nextLeft = stick == SkillMacro.Stick.LEFT ? direction : left;
            SkillMacro.Direction nextRight = stick == SkillMacro.Stick.RIGHT ? direction : right;
            if (nextLeft == left && nextRight == right) return;
            sendJoystick(nextLeft, nextRight);
            left = nextLeft;
            right = nextRight;
        }

        @Override public void resetController() throws Exception {
            if (keysDown.isEmpty() && left == null && right == null) return;
            IOException failure = new IOException("释放手柄输入失败");
            for (int key : List.copyOf(keysDown.keySet())) {
                try {
                    releaseKey(key);
                } catch (Exception error) {
                    failure.addSuppressed(error);
                }
            }
            if (left != null || right != null) {
                try {
                    sendJoystick(null, null);
                    left = right = null;
                } catch (Exception error) {
                    failure.addSuppressed(error);
                }
            }
            if (failure.getSuppressed().length > 0) throw failure;
        }
    }

    private void scan() {
        pending = null;
        GameTaskScheduler scheduler = taskScheduler;
        if (scheduler == null) return;
        if (scheduler.waitingForRelease() && manualTouchActive()) {
            pending = worker.schedule(this::scan, GameTaskScheduler.INTERACTION_INTERVAL_MS, TimeUnit.MILLISECONDS);
            return;
        }
        Bitmap screenshot = null;
        try {
            int id = display.getDisplay().getDisplayId();
            if (findGameTask(id, false) == null && restoreMovedTask(id)) {
                scheduler.pauseNormalTasks();
                pending = worker.schedule(this::scan, 700, TimeUnit.MILLISECONDS);
                return;
            }
            checkTarget();
            screenshot = copyFrame();
            GameTaskScheduler.State previous = scheduler.state();
            GameTaskScheduler.Tick tick = scheduler.runFrame(screenshot, this::tapWhenIdle);
            if (previous != scheduler.state()) logEvent(scheduler.state() == GameTaskScheduler.State.NORMAL
                    ? "返回普通状态" : "识别到选核桃页面");
            if (tick.status() != null && !tick.status().equals(message)) {
                message = tick.status();
                if (!message.startsWith("等待选核桃页面") && !message.startsWith("普通状态")) logEvent(message);
            }
            if (tick.finished()) {
                taskScheduler = null;
                phase = READY;
                return;
            }
            pending = worker.schedule(this::scan, tick.delayMs(), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            failTasks(error);
        } finally {
            if (screenshot != null) screenshot.recycle();
        }
    }

    private void failTasks(Exception error) {
        report(error);
        if (phase == RUNNING) {
            beginTaskStop(message);
            cancelTouches();
        }
    }

    private void checkTarget() throws Exception {
        Display target = display.getDisplay();
        Point size = new Point();
        target.getRealSize(size);
        int id = target.getDisplayId();
        if (id == Display.DEFAULT_DISPLAY || !target.isValid()) {
            throw new IOException("游戏虚拟屏已失效");
        }
        int displayState = target.getState();
        if (displayState != Display.STATE_ON) {
            throw new IOException("游戏虚拟屏未亮起 · 显示状态 " + displayState);
        }
        if (size.x != WIDTH || size.y != HEIGHT) {
            throw new IOException("游戏虚拟屏尺寸已变化 · " + size.x + "×" + size.y);
        }
        ComponentName activity = previewActivity(findGameTask(id, false));
        if (activity == null && phase == RUNNING) {
            throw new IOException("游戏画面已退出" + recentGameExit());
        }
        if (activity == null || !GAME.equals(activity.getPackageName())) {
            throw new IOException("请先进入 Warframe 游戏画面");
        }
    }

    private String recentGameExit() {
        try {
            ActivityManager manager = shellContext.getSystemService(ActivityManager.class);
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(GAME, 0, 4)) {
                if (exit.getTimestamp() >= gameStartedAt && GAME.equals(exit.getProcessName())) {
                    return " · Android 记录：" + TaskHistory.describeExit(exit.getReason());
                }
            }
        } catch (RuntimeException error) {
            Log.w("WarframeSurvival", "Could not read Warframe exit history", error);
        }
        return "";
    }

    private int manualTargetUid() throws Exception {
        Display target = display.getDisplay();
        int id = target.getDisplayId();
        if (id == Display.DEFAULT_DISPLAY || target.getState() != Display.STATE_ON) {
            throw new IOException("虚拟屏不可用");
        }
        ComponentName activity = previewActivity(findGameTask(id, false));
        if (activity != null) {
            String packageName = activity.getPackageName();
            if (GAME.equals(packageName)) return gameUid;
            Integer uid = loginWindowUids.get(packageName);
            if (uid == null) {
                uid = packageUid(packageName);
                loginWindowUids.put(packageName, uid);
            }
            return uid;
        }
        throw new IOException("当前窗口无法转发触摸");
    }

    private int packageUid(String name) throws Exception {
        String prefix = "package:" + name + " uid:";
        String uid = findLine(command("/system/bin/cmd", "package", "list", "packages",
                "-U", "--user", gameUser, name), prefix, "无法解析窗口应用的 UID");
        return Integer.parseInt(uid.substring(prefix.length()));
    }

    private ComponentName previewActivity(Object task) throws ReflectiveOperationException {
        if (task == null || !task.getClass().getField("visible").getBoolean(task)) return null;
        ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
        return top != null && (GAME.equals(top.getPackageName()) || loginWindows.contains(top)) ? top : null;
    }

    private boolean manualTouchActive() {
        return activeIds != null || queuedManualTouches.get() > 0
                || SystemClock.uptimeMillis() - lastManualTouch < MANUAL_TOUCH_SETTLE_MS;
    }

    private TapResult tapWhenIdle(String operation, Point point) throws Exception {
        if (manualTouchActive()) return TapResult.MANUAL_TOUCH_ACTIVE;
        try {
            sendPoint(MotionEvent.ACTION_DOWN, point);
            SystemClock.sleep(45);
            sendPoint(MotionEvent.ACTION_UP, point);
            return TapResult.SENT;
        } catch (InputRejectedException error) {
            cancelTouches();
            String detail = operation + " · 坐标 (" + point.x + ", " + point.y + ") · "
                    + error.getMessage() + " · " + touchWindowDetails() + " · 继续识别游戏画面";
            Log.w("WarframeInput", detail, error);
            logEvent(detail);
            return TapResult.REJECTED;
        } catch (Exception error) {
            cancelTouches();
            throw error;
        }
    }

    private String touchWindowDetails() {
        Display target = display.getDisplay();
        String window;
        try {
            Object task = findGameTask(target.getDisplayId(), false);
            if (task == null) {
                window = "未找到游戏任务";
            } else {
                Class<?> type = task.getClass();
                ComponentName top = (ComponentName) type.getField("topActivity").get(task);
                window = "游戏任务顶部 " + (top == null ? "未知" : top.flattenToShortString())
                        + " · 任务可见 " + type.getField("visible").getBoolean(task);
            }
        } catch (Exception error) {
            window = "读取游戏窗口失败 " + error.getClass().getSimpleName();
        }
        return window + " · 显示状态 " + target.getState();
    }

    private void sendPoint(int action, Point point) throws Exception {
        sendTouch(action, new int[]{0}, new float[]{point.x}, new float[]{point.y}, gameUid);
    }

    private void sendTouch(int action, int[] ids, float[] xs, float[] ys, int targetUid) throws Exception {
        int masked = action & MotionEvent.ACTION_MASK;
        int pointerIndex = (action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        if (ids == null || xs == null || ys == null || ids.length < 1 || ids.length > 10
                || ids.length != xs.length || ids.length != ys.length || pointerIndex >= ids.length) {
            throw new IllegalArgumentException("触点数据无效");
        }
        if (masked == MotionEvent.ACTION_DOWN) {
            cancelTouches();
            gestureStart = SystemClock.uptimeMillis();
            gestureUid = targetUid;
        } else if (activeIds == null) {
            return;
        }
        emitTouch(action, ids, xs, ys);
        switch (masked) {
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeIds = null;
            case MotionEvent.ACTION_POINTER_UP -> {
                activeIds = new int[ids.length - 1];
                activeXs = new float[activeIds.length];
                activeYs = new float[activeIds.length];
                for (int i = 0, j = 0; i < ids.length; i++) {
                    if (i == pointerIndex) continue;
                    activeIds[j] = ids[i];
                    activeXs[j] = xs[i];
                    activeYs[j++] = ys[i];
                }
            }
            default -> {
                activeIds = ids;
                activeXs = xs;
                activeYs = ys;
            }
        }
    }

    private void emitTouch(int action, int[] ids, float[] xs, float[] ys) throws Exception {
        int masked = action & MotionEvent.ACTION_MASK;
        int lifted = masked == MotionEvent.ACTION_POINTER_UP
                ? (action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT : -1;
        var pointers = new MotionEvent.PointerProperties[ids.length];
        var positions = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] > 31 || !Float.isFinite(xs[i]) || !Float.isFinite(ys[i])
                    || xs[i] < 0 || xs[i] >= WIDTH || ys[i] < 0 || ys[i] >= HEIGHT) {
                throw new IllegalArgumentException("触点超出虚拟屏");
            }
            pointers[i] = new MotionEvent.PointerProperties();
            pointers[i].id = ids[i];
            pointers[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            positions[i] = new MotionEvent.PointerCoords();
            positions[i].x = xs[i];
            positions[i].y = ys[i];
            positions[i].pressure = i == lifted || masked == MotionEvent.ACTION_UP ? 0 : 1;
            positions[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(gestureStart, SystemClock.uptimeMillis(), action, ids.length,
                pointers, positions, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            injectToGame(event, gestureUid);
        } finally {
            event.recycle();
        }
    }

    private void sendGamepad(int action, int key, long downTime) throws Exception {
        KeyEvent event = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, key,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD);
        injectToGame(event, gameUid);
    }

    private void sendJoystick(SkillMacro.Direction left, SkillMacro.Direction right) throws Exception {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords axes = new MotionEvent.PointerCoords();
        axes.setAxisValue(MotionEvent.AXIS_X, left == null ? 0 : left.x);
        axes.setAxisValue(MotionEvent.AXIS_Y, left == null ? 0 : left.y);
        axes.setAxisValue(MotionEvent.AXIS_Z, right == null ? 0 : right.x);
        axes.setAxisValue(MotionEvent.AXIS_RZ, right == null ? 0 : right.y);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{axes},
                0, 0, 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_JOYSTICK, 0);
        try {
            injectToGame(event, gameUid);
        } finally {
            event.recycle();
        }
    }

    private void injectToGame(InputEvent event, int targetUid) throws Exception {
        if (inputManager == null) {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            injectEvent = type.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            inputManager = type.getMethod("getInstance").invoke(null);
        }
        int displayId = display.getDisplay().getDisplayId();
        setDisplayId.invoke(event, displayId);
        try {
            if (!Boolean.TRUE.equals(injectEvent.invoke(inputManager, event, 1, targetUid))) {
                throw new InputRejectedException(event, displayId, targetUid, null);
            }
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof SecurityException denied) {
                throw new InputRejectedException(event, displayId, targetUid, denied);
            }
            throw error;
        }
    }

    private static final class InputRejectedException extends IOException {
        InputRejectedException(InputEvent event, int displayId, int targetUid, SecurityException denied) {
            super("Android 拒绝了" + describe(event) + " · 虚拟屏 " + displayId + " · 目标 UID " + targetUid
                    + " · " + (denied == null ? "输入接口返回 false，系统未提供具体原因" : denied), denied);
        }

        private static String describe(InputEvent event) {
            if (event instanceof KeyEvent key) {
                return "手柄按键 " + (key.getAction() == KeyEvent.ACTION_DOWN ? "DOWN " : "UP ")
                        + KeyEvent.keyCodeToString(key.getKeyCode());
            }
            MotionEvent motion = (MotionEvent) event;
            String source = motion.isFromSource(InputDevice.SOURCE_JOYSTICK) ? "摇杆事件" : "触摸事件";
            return source + " " + MotionEvent.actionToString(motion.getAction());
        }
    }

    private void cancelTouches() {
        if (activeIds == null) return;
        try {
            emitTouch(MotionEvent.ACTION_CANCEL, activeIds, activeXs, activeYs);
        } catch (Exception error) {
            Log.w("WarframeSurvival", "Could not cancel touch gesture", error);
        } finally {
            activeIds = null;
        }
    }

    @Override public void stopSurvival() {
        worker.execute(() -> {
            if (taskScheduler == null) return;
            beginTaskStop(taskScheduler.stopMessage());
        });
    }

    private void beginTaskStop(String result) {
        cancelPending();
        detachTasks();
        completeTaskStop(result, INPUT_RELEASE_RETRIES);
    }

    private void completeTaskStop(String result, int retries) {
        pending = null;
        Exception failure = tryReleaseTaskInputs();
        if (failure != null && retries > 0) {
            message = "正在停止任务 · 释放宏输入";
            pending = worker.schedule(() -> completeTaskStop(result, retries - 1),
                    INPUT_RELEASE_RETRY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        String status = result;
        if (failure != null) {
            Exception detail = failure.getSuppressed().length > 0
                    && failure.getSuppressed()[0] instanceof Exception suppressed ? suppressed : failure;
            logEvent("释放宏输入失败 · " + detail.getMessage());
            status += INPUT_RELEASE_PENDING;
        }
        if (!status.equals(message)) logEvent(status);
        message = status;
        phase = READY;
    }

    @Override public void lockScreen() {
        displayPowerWorker.execute(() -> {
            try {
                DisplayManager manager = shellContext.getSystemService(DisplayManager.class);
                Display mainDisplay = manager.getDisplay(Display.DEFAULT_DISPLAY);
                if (mainDisplay == null) throw new IOException("未找到手机主屏");
                command("/system/bin/input", "-d", String.valueOf(Display.DEFAULT_DISPLAY), "keyevent", "KEYCODE_SLEEP");
                long deadline = SystemClock.uptimeMillis() + 1_200;
                do {
                    int state = mainDisplay.getState();
                    if (state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) {
                        logEvent("手机主屏已熄灭");
                        return;
                    }
                    SystemClock.sleep(100);
                } while (SystemClock.uptimeMillis() < deadline);
                throw new IOException("主屏仍亮着，请查看系统锁屏设置");
            } catch (Exception error) {
                report(new IOException("锁屏失败 · " + error.getMessage(), error));
            }
        });
    }

    @Override public void closeGame() {
        worker.execute(() -> {
            release();
            message = "游戏已关闭";
            logEvent(message);
        });
    }

    @Override public void destroy() {
        worker.execute(() -> {
            try {
                release();
            } finally {
                displayPowerWorker.shutdownNow();
                if (previewSurface != null) previewSurface.release();
                System.exit(0);
            }
        });
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private void detachTasks() {
        if (taskScheduler == null) return;
        releasingTasks = taskScheduler;
        taskScheduler = null;
    }

    private void releaseTaskInputs() throws Exception {
        if (releasingTasks == null) return;
        releasingTasks.pauseNormalTasks();
        releasingTasks = null;
    }

    private Exception tryReleaseTaskInputs() {
        try {
            releaseTaskInputs();
            return null;
        } catch (Exception error) {
            Log.w("WarframeSurvival", "Could not release macro input", error);
            return error;
        }
    }

    private void release() {
        cancelPending();
        if (powerKeepAlive != null) powerKeepAlive.cancel(false);
        powerKeepAlive = null;
        if (gameTaskWatch != null) gameTaskWatch.cancel(false);
        gameTaskWatch = null;
        trackedTaskId = recentsHandledTaskId = -1;
        lastTaskMove = 0;
        detachTasks();
        tryReleaseTaskInputs();
        releasingTasks = null;
        cancelTouches();
        phase = CLOSED;
        if (virtualGameDisplay != null) virtualGameDisplay.close();
        virtualGameDisplay = null;
        display = null;
        loginWindows = Set.of();
        GpuPreview active = renderer;
        renderer = null;
        if (active != null) active.close();
    }

    private static String findLine(String output, String prefix, String error) throws IOException {
        return output.lines().map(String::strip).filter(line -> line.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new IOException(error));
    }

    static String command(String... args) throws Exception {
        java.lang.Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        FutureTask<byte[]> read = new FutureTask<>(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readAllBytes();
            }
        });
        new Thread(read, "wf-shell-output").start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("系统命令超时");
            String output = new String(read.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException(output.trim());
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private void report(Exception error) {
        Throwable cause = error;
        while ((cause instanceof java.lang.reflect.InvocationTargetException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String detail = cause.getMessage();
        message = "操作失败 · " + (detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail);
        Log.w("WarframeSurvival", message, error);
        logEvent(message);
    }
}
