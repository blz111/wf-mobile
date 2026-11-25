package dev.wfassistant.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class VirtualGameSession extends ISurvivalSession.Stub {
    static final int SHELL_UID = 2000;
    static final int CLOSED = 0, OPENING = 1, READY = 2, RUNNING = 3;
    static final int WIDTH = 1280, HEIGHT = 720;
    private static final int RELIC_SCAN_INTERVAL_MS = 5_000;
    private static final int CONFIRM_SCAN_INTERVAL_MS = 300;
    private static final int CONFIRM_TIMEOUT_MS = 4_000;
    private static final int MANUAL_TOUCH_SETTLE_MS = 250;
    private static final int GAME_TASK_WATCH_MS = 1_500;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int DISPLAY_ALWAYS_UNLOCKED = 1 << 9;
    private static final String GAME = "com.digitalextremes.warframemobile";
    private static final Pattern VIBRATION_MODE = Pattern.compile("(?m)^\\s*(?:VIBRATE:|Default mode:)\\s*(\\w+)");
    private static final ComponentName GOOGLE_CHOOSER = new ComponentName("com.google.android.gms",
            "com.google.android.gms.auth.api.credentials.assistedsignin.ui.GoogleSignInActivity");
    private static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | (1 << 6)   // SUPPORTS_TOUCH
            | (1 << 8)   // DESTROY_CONTENT_ON_REMOVAL
            | (1 << 10)  // TRUSTED
            | (1 << 11)  // OWN_DISPLAY_GROUP
            | (1 << 14)  // OWN_FOCUS
            | (1 << 16); // STEAL_TOP_FOCUS_DISABLED
    private static final int NON_STEALING_FOCUS = (1 << 11) | (1 << 12);

    private final Context shellContext;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final AtomicInteger queuedManualTouches = new AtomicInteger();
    private final SurvivalRecognition recognition = new SurvivalRecognition();
    private volatile String message = "Shizuku 已连接";
    private volatile int phase = CLOSED;
    private VirtualDisplay display;
    private volatile GpuPreview renderer;
    private Surface previewSurface;
    private int previewWidth, previewHeight;
    private int gameUid;
    private int googleUid = -1;
    private int gestureUid;
    private String gameUser;
    private int trackedTaskId = -1;
    private int recentsHandledTaskId = -1;
    private long lastTaskMove;
    private ScheduledFuture<?> pending;
    private ScheduledFuture<?> powerKeepAlive;
    private ScheduledFuture<?> gameTaskWatch;
    private boolean waitingForRelicsToClose;
    private boolean countRelicOnClose;
    private boolean waitingForManualRelease;
    private int relicLimit;
    private long equippedRelics;
    private long confirmationDeadline;
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
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
        };
        worker.setRemoveOnCancelPolicy(true);
    }

    @Override public String status() { return message; }
    @Override public int phase() { return phase; }

    @Override public boolean isGameVibrationMuted() {
        try {
            return readGameVibrationMuted(currentUser());
        } catch (Exception error) {
            throw new IllegalStateException("读取 Warframe 震动设置失败", error);
        }
    }

    @Override public boolean setGameVibrationMuted(boolean muted) {
        try {
            String user = currentUser();
            command("/system/bin/cmd", "appops", "set", "--user", user, GAME, "VIBRATE",
                    muted ? "ignore" : "allow");
            if (readGameVibrationMuted(user) != muted) {
                throw new IOException("Android 未应用震动设置");
            }
            return muted;
        } catch (Exception error) {
            throw new IllegalStateException("修改 Warframe 震动设置失败", error);
        }
    }

    private static boolean readGameVibrationMuted(String user) throws Exception {
        String result = command("/system/bin/cmd", "appops", "get", "--user", user, GAME, "VIBRATE");
        var mode = VIBRATION_MODE.matcher(result);
        if (!mode.find()) throw new IOException("系统未返回 Warframe 震动状态");
        return switch (mode.group(1)) {
            case "ignore", "deny", "errored" -> true;
            case "allow", "default", "foreground" -> false;
            default -> throw new IOException("未知震动状态 · " + mode.group(1));
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
                googleUid = -1;
                String component = findLine(command("/system/bin/cmd", "package", "resolve-activity",
                        "--brief", "--user", gameUser, "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", GAME),
                        GAME + "/", "当前用户未安装 Warframe");
                gameUid = packageUid(GAME);
                createDisplay();
                int id = display.getDisplay().getDisplayId();
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

    private void createDisplay() throws Exception {
        renderer = new GpuPreview(WIDTH, HEIGHT);
        DisplayManager manager = DisplayManager.class.getConstructor(Context.class).newInstance(shellContext);
        try {
            display = manager.createVirtualDisplay("Warframe Assistant", WIDTH, HEIGHT, 280,
                    renderer.inputSurface(), DISPLAY_FLAGS | ALWAYS_UNLOCKED);
        } catch (SecurityException error) {
            Log.w("WarframePower", "Always-unlocked display permission unavailable", error);
            display = manager.createVirtualDisplay("Warframe Assistant", WIDTH, HEIGHT, 280,
                    renderer.inputSurface(), DISPLAY_FLAGS);
        }
        if (display == null || display.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY
                || (display.getDisplay().getFlags() & NON_STEALING_FOCUS) != NON_STEALING_FOCUS) {
            throw new IOException("系统未创建具备独立焦点的虚拟屏");
        }
        attachPreview();
        startDisplayKeepAlive(display.getDisplay());
    }

    private void startDisplayKeepAlive(Display target) {
        try {
            int id = target.getDisplayId();
            VirtualDisplayPower power = new VirtualDisplayPower(id);
            Log.i("WarframePower", "Display " + id + " keep-alive enabled, alwaysUnlocked="
                    + ((target.getFlags() & DISPLAY_ALWAYS_UNLOCKED) != 0));
            powerKeepAlive = worker.scheduleWithFixedDelay(() -> {
                try {
                    if (target.isValid()) power.keepAwake();
                } catch (ReflectiveOperationException error) {
                    Log.w("WarframePower", "Could not keep virtual display awake", error);
                }
            }, 0, 4, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WarframePower", "Display keep-alive unavailable", error);
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

    @Override public void startSurvival(int limit) {
        worker.execute(() -> {
            if (phase != READY) return;
            if (limit < 0) {
                message = "开核桃数需要是非负整数";
                return;
            }
            relicLimit = limit;
            equippedRelics = 0;
            waitingForRelicsToClose = false;
            countRelicOnClose = false;
            waitingForManualRelease = false;
            confirmationDeadline = 0;
            phase = RUNNING;
            scan();
        });
    }

    private void scan() {
        pending = null;
        if (waitingForManualRelease && manualTouchActive()) {
            pending = worker.schedule(this::scan, CONFIRM_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            return;
        }
        waitingForManualRelease = false;
        Bitmap screenshot = null;
        try {
            int id = display.getDisplay().getDisplayId();
            if (findGameTask(id, false) == null && restoreMovedTask(id)) {
                pending = worker.schedule(this::scan, 700, TimeUnit.MILLISECONDS);
                return;
            }
            checkTarget();
            screenshot = copyFrame();
            int delay = RELIC_SCAN_INTERVAL_MS;
            if (confirmationDeadline != 0) {
                if (screenshot != null && recognition.relicConfirmation(screenshot)) {
                    if (tapWhenIdle(new Point(505, 402))) {
                        confirmationDeadline = 0;
                        waitingForRelicsToClose = true;
                        countRelicOnClose = true;
                        survivalMessage("已点击确认装备核桃");
                    } else {
                        delay = CONFIRM_SCAN_INTERVAL_MS;
                        survivalMessage("等待松手后确认装备核桃");
                    }
                } else if (SystemClock.uptimeMillis() >= confirmationDeadline) {
                    confirmationDeadline = 0;
                    waitingForRelicsToClose = true;
                    survivalMessage("未识别到核桃确认框 · 等待页面关闭");
                } else {
                    delay = CONFIRM_SCAN_INTERVAL_MS;
                }
            } else if (waitingForRelicsToClose) {
                if (screenshot != null && !recognition.relicSelection(screenshot)
                        && !recognition.relicConfirmation(screenshot)) {
                    waitingForRelicsToClose = false;
                    if (countRelicOnClose) {
                        countRelicOnClose = false;
                        equippedRelics++;
                        if (relicLimit > 0 && equippedRelics >= relicLimit) {
                            survivalMessage("已达到开核桃数，停止检测");
                            phase = READY;
                            return;
                        }
                    }
                    survivalMessage("等待选核桃页面");
                }
            } else if (screenshot != null && recognition.relicSelection(screenshot)) {
                delay = CONFIRM_SCAN_INTERVAL_MS;
                if (tapWhenIdle(new Point(809, 606))) {
                    confirmationDeadline = SystemClock.uptimeMillis() + CONFIRM_TIMEOUT_MS;
                    survivalMessage("已点击装备以执行任务 · 等待确认框");
                } else {
                    survivalMessage("等待松手后装备核桃");
                }
            } else {
                survivalMessage("等待选核桃页面");
            }
            pending = worker.schedule(this::scan, delay, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            phase = READY;
            report(error);
        } finally {
            if (screenshot != null) screenshot.recycle();
        }
    }

    private void survivalMessage(String status) {
        String progress = relicLimit == 0 ? "已装备 " + equippedRelics + " 个"
                : "开核桃 " + equippedRelics + "/" + relicLimit;
        message = status + " · " + progress;
    }

    private void checkTarget() throws Exception {
        Display target = display.getDisplay();
        Point size = new Point();
        target.getRealSize(size);
        int id = target.getDisplayId();
        if (id == Display.DEFAULT_DISPLAY || target.getState() != Display.STATE_ON
                || size.x != WIDTH || size.y != HEIGHT) {
            throw new IOException("Warframe 不在可操作的虚拟屏，或虚拟屏尺寸已变化");
        }
        ComponentName activity = previewActivity(findGameTask(id, false));
        if (activity == null || !GAME.equals(activity.getPackageName())) {
            throw new IOException("请先进入 Warframe 游戏画面");
        }
    }

    private int manualTargetUid() throws Exception {
        Display target = display.getDisplay();
        int id = target.getDisplayId();
        if (id == Display.DEFAULT_DISPLAY || target.getState() != Display.STATE_ON) {
            throw new IOException("虚拟屏不可用");
        }
        ComponentName activity = previewActivity(findGameTask(id, false));
        if (GOOGLE_CHOOSER.equals(activity)) {
            if (googleUid < 0) googleUid = packageUid(GOOGLE_CHOOSER.getPackageName());
            return googleUid;
        }
        if (activity != null && GAME.equals(activity.getPackageName())) return gameUid;
        throw new IOException("当前窗口无法转发触摸");
    }

    private int packageUid(String name) throws Exception {
        String prefix = "package:" + name + " uid:";
        String uid = findLine(command("/system/bin/cmd", "package", "list", "packages",
                "-U", "--user", gameUser, name), prefix, "无法解析窗口应用的 UID");
        return Integer.parseInt(uid.substring(prefix.length()));
    }

    private static ComponentName previewActivity(Object task) throws ReflectiveOperationException {
        if (task == null || !task.getClass().getField("visible").getBoolean(task)) return null;
        ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
        return top != null && (GAME.equals(top.getPackageName()) || GOOGLE_CHOOSER.equals(top)) ? top : null;
    }

    private boolean manualTouchActive() {
        return activeIds != null || queuedManualTouches.get() > 0
                || SystemClock.uptimeMillis() - lastManualTouch < MANUAL_TOUCH_SETTLE_MS;
    }

    private boolean tapWhenIdle(Point point) throws Exception {
        if (manualTouchActive()) {
            waitingForManualRelease = true;
            return false;
        }
        try {
            sendPoint(MotionEvent.ACTION_DOWN, point);
            SystemClock.sleep(45);
            sendPoint(MotionEvent.ACTION_UP, point);
            return true;
        } catch (Exception error) {
            cancelTouches();
            throw error;
        }
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
        if (inputManager == null) {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            injectEvent = type.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            inputManager = type.getMethod("getInstance").invoke(null);
        }
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
            setDisplayId.invoke(event, display.getDisplay().getDisplayId());
            if (!Boolean.TRUE.equals(injectEvent.invoke(inputManager, event, 1, gestureUid))) {
                throw new IOException("Android 拒绝了当前窗口的触摸事件");
            }
        } finally {
            event.recycle();
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
            if (phase != RUNNING) return;
            cancelPending();
            waitingForManualRelease = false;
            confirmationDeadline = 0;
            phase = READY;
            survivalMessage("生存任务已停止");
        });
    }

    @Override public void closeGame() {
        worker.execute(() -> {
            release();
            message = "游戏已关闭";
        });
    }

    @Override public void destroy() {
        worker.execute(() -> {
            try {
                release();
            } finally {
                if (previewSurface != null) previewSurface.release();
                System.exit(0);
            }
        });
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private void release() {
        cancelPending();
        if (powerKeepAlive != null) powerKeepAlive.cancel(false);
        powerKeepAlive = null;
        if (gameTaskWatch != null) gameTaskWatch.cancel(false);
        gameTaskWatch = null;
        trackedTaskId = recentsHandledTaskId = -1;
        lastTaskMove = 0;
        cancelTouches();
        confirmationDeadline = 0;
        waitingForRelicsToClose = false;
        countRelicOnClose = false;
        waitingForManualRelease = false;
        phase = CLOSED;
        if (display != null) display.release();
        display = null;
        GpuPreview active = renderer;
        renderer = null;
        if (active != null) active.close();
    }

    private static String findLine(String output, String prefix, String error) throws IOException {
        return output.lines().map(String::strip).filter(line -> line.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new IOException(error));
    }

    private static String command(String... args) throws Exception {
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
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = cause.getMessage();
        message = "操作失败 · " + (detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail);
    }
}
