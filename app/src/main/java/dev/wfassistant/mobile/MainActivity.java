package dev.wfassistant.mobile;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService statusWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService screenshotWorker = Executors.newSingleThreadExecutor();
    private Shizuku.UserServiceArgs args;
    private ISurvivalSession session;
    private boolean bound, visible, polling, expanded, touching, saving, hasGameFrame;
    private boolean vibrationBusy;
    private Boolean vibrationMuted;
    private int phase = VirtualGameSession.CLOSED;
    private FrameLayout smallPreview, fullPreview;
    private ScrollView compact;
    private TextView state;
    private TextureView preview;
    private Surface previewSurface;
    private int previewWidth, previewHeight;
    private Button authorize, launch, close, start, stop, screenshot, vibration;
    private CheckBox relicTask;
    private EditText relicLimit;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            session = ISurvivalSession.Stub.asInterface(binder);
            updatePreviewSurface();
            updateControls();
            updateVibration(null);
            poll();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            disconnected("Shizuku 用户服务已断开");
        }
    };

    private final Shizuku.OnBinderReceivedListener received = () -> connect(false);
    private final Shizuku.OnBinderDeadListener dead = () -> disconnected("请先启动 Shizuku");
    private final Shizuku.OnRequestPermissionResultListener permission = (request, result) -> {
        if (request != REQUEST_SHIZUKU) return;
        if (result == PackageManager.PERMISSION_GRANTED) connect(false);
        else showStatus("请在 Shizuku 中授权本应用");
    };
    private final Runnable nextPoll = this::poll;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, VirtualGameSession.class))
                .processNameSuffix("survival_session").daemon(false).version(14);
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        buildUi();
        applySystemBars();
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        connect(false);
        updatePreviewSurface();
        updateVibration(null);
        poll();
    }

    @Override protected void onStop() {
        endGesture();
        visible = false;
        updatePreviewSurface();
        ui.removeCallbacks(nextPoll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (bound && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (RuntimeException ignored) {
                // The Binder may have disconnected during teardown.
            }
        }
        bound = false;
        session = null;
        ui.removeCallbacksAndMessages(null);
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
        statusWorker.shutdownNow();
        screenshotWorker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (expanded) setExpanded(false);
        else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applySystemBars();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        compact = new ScrollView(this);
        compact.setFillViewport(true);
        compact.setBackgroundColor(Color.rgb(244, 248, 247));
        compact.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setFocusableInTouchMode(true);
        body.setPadding(dp(20), dp(16), dp(20), dp(20));
        compact.addView(body);
        root.addView(compact);
        Screen.heading(body, "Warframe 生存");

        smallPreview = new FrameLayout(this);
        smallPreview.setBackgroundColor(Color.rgb(21, 35, 39));
        body.addView(smallPreview, new LinearLayout.LayoutParams(-1, dp(200)));
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (previewSurface != null) previewSurface.release();
                previewSurface = new Surface(texture);
                onSurfaceTextureSizeChanged(texture, width, height);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                previewWidth = width;
                previewHeight = height;
                updatePreviewSurface();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                Surface previous = previewSurface;
                previewSurface = null;
                updatePreviewSurface();
                if (previous != null) previous.release();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        preview.setAlpha(0);
        preview.setContentDescription("Warframe 预览，轻触放大");
        preview.setOnClickListener(view -> setExpanded(true));
        preview.setOnTouchListener((view, event) -> expanded && forwardTouch(event));
        smallPreview.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        state = Screen.text(body, "");
        showStatus("正在检查 Shizuku…");
        authorize = Screen.button(body, "连接 Shizuku", () -> connect(true));
        LinearLayout gameControls = row(body);
        launch = rowButton(gameControls, "启动 Warframe", () -> call(ISurvivalSession::launch));
        close = rowButton(gameControls, "关闭游戏", () -> call(ISurvivalSession::closeGame));
        screenshot = Screen.button(body, "保存截图", this::saveScreenshot);
        vibration = Screen.button(body, "读取游戏震动", () ->
                updateVibration(vibrationMuted == null ? null : !vibrationMuted));
        relicTask = new CheckBox(this);
        relicTask.setText("识别核桃");
        relicTask.setChecked(getPreferences(MODE_PRIVATE).getBoolean("relic_task_enabled", true));
        body.addView(relicTask);
        LinearLayout relicSettings = row(body);
        relicSettings.setGravity(Gravity.CENTER_VERTICAL);
        relicSettings.setPaddingRelative(dp(24), 0, 0, 0);
        TextView relicLimitLabel = Screen.text(relicSettings, "开核桃数（0 不限）");
        relicLimit = new EditText(this);
        relicLimit.setId(View.generateViewId());
        relicLimitLabel.setLabelFor(relicLimit.getId());
        relicLimit.setSingleLine();
        relicLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        relicLimit.setText(String.valueOf(getPreferences(MODE_PRIVATE).getInt("relic_limit", 0)));
        relicSettings.addView(relicLimit, new LinearLayout.LayoutParams(dp(92), -2));
        LinearLayout taskControls = row(body);
        start = rowButton(taskControls, "开始任务", this::startSurvival);
        stop = rowButton(taskControls, "停止", () -> call(ISurvivalSession::stopSurvival));
        relicTask.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("relic_task_enabled", checked).apply();
            updateControls();
        });

        fullPreview = new FrameLayout(this);
        fullPreview.setVisibility(View.GONE);
        root.addView(fullPreview, new FrameLayout.LayoutParams(-1, -1));
        ImageButton closePreview = new ImageButton(this);
        closePreview.setImageResource(R.drawable.ic_close_preview);
        closePreview.setContentDescription("退出放大预览");
        closePreview.setScaleType(ImageView.ScaleType.CENTER);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        closePreview.setBackgroundResource(ripple.resourceId);
        closePreview.setOnClickListener(view -> setExpanded(false));
        FrameLayout.LayoutParams controls = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        controls.topMargin = dp(8);
        controls.setMarginEnd(dp(8));
        fullPreview.addView(closePreview, controls);
        fullPreview.setOnApplyWindowInsetsListener((view, insets) -> {
            var cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            closePreview.setTranslationX(-cutout.right);
            closePreview.setTranslationY(cutout.top);
            return insets;
        });
        setContentView(root);
        body.requestFocus();
        updateControls();
    }

    private void startSurvival() {
        String text = relicLimit.getText().toString().trim();
        int limit;
        try {
            limit = text.isEmpty() ? 0 : Integer.parseInt(text);
            if (limit < 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            relicLimit.setError("请输入有效的非负整数");
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putInt("relic_limit", limit).apply();
        relicLimit.setText(String.valueOf(limit));
        relicLimit.clearFocus();
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.hide(WindowInsets.Type.ime());
        call(service -> service.startSurvival(limit));
    }

    private void setExpanded(boolean show) {
        if (expanded == show || (show && !hasGameFrame)) return;
        endGesture();
        expanded = show;
        if (show) {
            smallPreview.removeView(preview);
            preview.setContentDescription("可操作的 Warframe 画面");
            fullPreview.addView(preview, 0, new FrameLayout.LayoutParams(-1, -1));
        } else {
            fullPreview.removeView(preview);
            preview.setContentDescription("Warframe 预览，轻触放大");
            smallPreview.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        }
        compact.setVisibility(show ? View.GONE : View.VISIBLE);
        fullPreview.setVisibility(show ? View.VISIBLE : View.GONE);
        applySystemBars();
        setRequestedOrientation(show ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void applySystemBars() {
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars == null) return;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        bars.setSystemBarsAppearance(expanded ? 0 : light, light);
        if (expanded) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        } else {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
            bars.show(WindowInsets.Type.systemBars());
        }
    }

    private void updatePreviewSurface() {
        Surface target = visible ? previewSurface : null;
        call(service -> service.setPreviewSurface(target, previewWidth, previewHeight));
    }

    private boolean forwardTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            endGesture();
            return true;
        }
        if ((phase != VirtualGameSession.READY && phase != VirtualGameSession.RUNNING)
                || session == null || !hasGameFrame) return true;
        if (action != MotionEvent.ACTION_DOWN && !touching) return true;
        Rect bounds = GpuPreview.fit(preview.getWidth(), preview.getHeight(),
                VirtualGameSession.WIDTH, VirtualGameSession.HEIGHT);
        if (bounds.isEmpty()) return true;
        if (action == MotionEvent.ACTION_DOWN && !bounds.contains((int) event.getX(), (int) event.getY())) {
            return true;
        }
        int count = event.getPointerCount();
        int[] ids = new int[count];
        float[] xs = new float[count], ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = Math.max(0, Math.min(VirtualGameSession.WIDTH - 1,
                    (event.getX(i) - bounds.left) * VirtualGameSession.WIDTH / bounds.width()));
            ys[i] = Math.max(0, Math.min(VirtualGameSession.HEIGHT - 1,
                    (event.getY(i) - bounds.top) * VirtualGameSession.HEIGHT / bounds.height()));
        }
        touching = action != MotionEvent.ACTION_UP;
        call(service -> service.touch(event.getAction(), ids, xs, ys));
        return true;
    }

    private void endGesture() {
        if (!touching) return;
        touching = false;
        call(service -> service.touch(MotionEvent.ACTION_CANCEL, null, null, null));
    }

    private void saveScreenshot() {
        ISurvivalSession active = session;
        if (active == null || saving) return;
        saving = true;
        updateControls();
        screenshotWorker.execute(() -> {
            Uri uri = null;
            String message;
            var resolver = getContentResolver();
            try {
                ContentValues values = new ContentValues();
                String time = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Warframe-" + time + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WarframeSurvival");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("无法创建截图文件");
                try (ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "w")) {
                    if (output == null || !active.saveScreenshot(output)) throw new IOException("还没有可保存的画面");
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                message = "已保存 1280 × 720 截图 · " + time;
            } catch (Exception error) {
                if (uri != null) resolver.delete(uri, null, null);
                message = "截图失败 · " + error.getMessage();
            }
            String result = message;
            ui.post(() -> {
                if (isDestroyed()) return;
                saving = false;
                updateControls();
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void poll() {
        ISurvivalSession active = session;
        if (!visible || active == null || polling) return;
        ui.removeCallbacks(nextPoll);
        polling = true;
        statusWorker.execute(() -> {
            try {
                int currentPhase = active.phase();
                String message = active.status();
                boolean frameAvailable = currentPhase != VirtualGameSession.CLOSED && active.hasFrame();
                ui.post(() -> {
                    if (session != active || !visible) return;
                    phase = currentPhase;
                    if (showStatus(message) && expanded && message.startsWith("操作失败")) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                    if (hasGameFrame != frameAvailable) {
                        hasGameFrame = frameAvailable;
                        preview.setAlpha(frameAvailable ? 1 : 0);
                    }
                    updateControls();
                });
            } catch (RemoteException error) {
                ui.post(() -> {
                    if (session == active) disconnected("Shizuku 用户服务已断开");
                });
            } catch (RuntimeException error) {
                ui.post(() -> {
                    if (session == active) showStatus("虚拟屏预览失败 · " + error.getMessage());
                });
            } finally {
                ui.post(() -> {
                    polling = false;
                    if (visible && session != null) ui.postDelayed(nextPoll, 250);
                });
            }
        });
    }

    private boolean showStatus(String message) {
        String text = "状态：" + message;
        if (text.contentEquals(state.getText())) return false;
        state.setText(text);
        return true;
    }

    private void updateVibration(Boolean muted) {
        ISurvivalSession active = session;
        if (active == null || vibrationBusy) return;
        vibrationBusy = true;
        updateControls();
        statusWorker.execute(() -> {
            try {
                boolean result = muted == null ? active.isGameVibrationMuted()
                        : active.setGameVibrationMuted(muted);
                ui.post(() -> {
                    if (session != active) return;
                    vibrationBusy = false;
                    vibrationMuted = result;
                    updateControls();
                });
            } catch (RemoteException | RuntimeException error) {
                ui.post(() -> {
                    if (session != active) return;
                    vibrationBusy = false;
                    vibrationMuted = null;
                    updateControls();
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void connect(boolean interactive) {
        if (bound || isDestroyed()) return;
        if (!Shizuku.pingBinder()) {
            showStatus("请先启动 Shizuku");
            return;
        }
        try {
            if (Shizuku.getUid() != VirtualGameSession.SHELL_UID || Shizuku.getVersion() < 13) {
                showStatus("请通过无线调试或 ADB 启动 Shizuku 13 或以上版本");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                showStatus("等待 Shizuku 授权");
                if (interactive) Shizuku.requestPermission(REQUEST_SHIZUKU);
                return;
            }
            showStatus("正在连接 Shizuku 用户服务…");
            bound = true;
            Shizuku.bindUserService(args, connection);
            ui.postDelayed(() -> {
                if (bound && session == null) {
                    try {
                        Shizuku.unbindUserService(args, connection, true);
                    } catch (RuntimeException ignored) {
                        // The service may have stopped before the timeout.
                    }
                    disconnected("连接 Shizuku 用户服务超时");
                }
            }, 10_000);
        } catch (RuntimeException error) {
            disconnected("连接失败 · " + error.getMessage());
        }
    }

    private void disconnected(String message) {
        touching = false;
        session = null;
        bound = false;
        phase = VirtualGameSession.CLOSED;
        vibrationBusy = false;
        vibrationMuted = null;
        hasGameFrame = false;
        preview.setAlpha(0);
        showStatus(message);
        updateControls();
    }

    private void updateControls() {
        boolean connected = session != null;
        boolean hasFrame = connected && hasGameFrame;
        authorize.setEnabled(!bound);
        launch.setEnabled(connected && phase == VirtualGameSession.CLOSED);
        close.setEnabled(connected && phase != VirtualGameSession.CLOSED);
        start.setEnabled(connected && phase == VirtualGameSession.READY && relicTask.isChecked());
        stop.setEnabled(connected && phase == VirtualGameSession.RUNNING);
        relicTask.setEnabled(phase != VirtualGameSession.RUNNING);
        relicLimit.setEnabled(phase != VirtualGameSession.RUNNING && relicTask.isChecked());
        screenshot.setEnabled(hasFrame && !saving);
        vibration.setEnabled(connected && !vibrationBusy);
        vibration.setText(vibrationBusy ? "正在更新游戏震动…" : vibrationMuted == null ? "读取游戏震动"
                : vibrationMuted ? "恢复游戏震动" : "关闭游戏震动");
    }

    private void call(SessionAction action) {
        if (session == null) return;
        try {
            action.run(session);
        } catch (RemoteException error) {
            disconnected("Shizuku 用户服务已断开");
        }
    }

    private int dp(int value) { return Screen.dp(this, value); }

    private LinearLayout row(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        body.addView(row);
        return row;
    }

    private Button rowButton(LinearLayout row, String label, Runnable action) {
        Button button = Screen.button(row, label, action);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    @FunctionalInterface
    private interface SessionAction {
        void run(ISurvivalSession service) throws RemoteException;
    }
}
