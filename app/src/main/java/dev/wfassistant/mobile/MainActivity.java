package dev.wfassistant.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int TASK_TAB = 0, MACRO_TAB = 1, TAKEOVER_TAB = 2, LOG_TAB = 3;
    private static final int INK = 0xff18312c, ACCENT = 0xff216958, PALE = 0xffe1eee8;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService statusWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService screenshotWorker = Executors.newSingleThreadExecutor();
    private GameConnection gameConnection;
    private TaskHistory taskHistory;
    private ISurvivalSession session;
    private boolean visible, polling, expanded, touching, saving, hasGameFrame;
    private boolean takeoverActive, takeoverSwitching;
    private final boolean[] feedbackBusy = new boolean[2];
    private final Boolean[] feedbackMuted = new Boolean[2];
    private int phase = VirtualGameSession.CLOSED;
    private FrameLayout smallPreview, fullPreview;
    private ScrollView compact;
    private TextView state, taskLog, pickingHint;
    private TextureView preview;
    private Surface previewSurface;
    private int previewWidth, previewHeight;
    private Button authorize, launch, close, start, screenshot, lockScreen, vibration, audio, quickOptions;
    private Button battery, notifications, takeoverButton;
    private VirtualGamepadView gamepad;
    private final Button[] tabs = new Button[4];
    private final LinearLayout[] panels = new LinearLayout[4];
    private LinearLayout tabContent, relicSettings, macroSettings, quickPanel, footer;
    private TextView relicLabel, macroLabel;
    private CheckBox relicTask, macroTask;
    private EditText relicLimit;
    private Spinner macroPicker;
    private MacroEditor macroEditor;
    private int activeTab = TASK_TAB;
    private Consumer<Point> pointReceiver;
    private final OnBackInvokedCallback closeExpandedPreview = () -> setExpanded(false);

    private final GameConnection.Listener connectionListener = this::connectionChanged;
    private final Runnable nextPoll = this::poll;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        gameConnection = AssistantApplication.connection(this);
        taskHistory = new TaskHistory(this);
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        buildUi();
        applySystemBars();
        gameConnection.attach(connectionListener);
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        updateTakeoverControls();
        gameConnection.connect(false);
        updatePreviewSurface();
        refreshTaskLog(null);
        if (quickPanel.getVisibility() == View.VISIBLE) readFeedback();
        poll();
    }

    @Override protected void onResume() {
        super.onResume();
        updateBackgroundOptions();
    }

    @Override protected void onStop() {
        endGesture();
        visible = false;
        updateTakeoverControls();
        if (takeoverActive) call(ISurvivalSession::releaseManualGamepad);
        updatePreviewSurface();
        ui.removeCallbacks(nextPoll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (expanded) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeExpandedPreview);
        gameConnection.detach(connectionListener);
        session = null;
        ui.removeCallbacksAndMessages(null);
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
        statusWorker.shutdownNow();
        screenshotWorker.shutdown();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applySystemBars();
        else if (gamepad != null) gamepad.releaseAll();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        compact = new ScrollView(this) {
            @Override protected void measureChildWithMargins(View child, int widthSpec, int widthUsed,
                                                             int heightSpec, int heightUsed) {
                if (activeTab != LOG_TAB) {
                    super.measureChildWithMargins(child, widthSpec, widthUsed, heightSpec, heightUsed);
                    return;
                }
                // The log tab uses the screen as its viewport and scrolls only its log pane.
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                int horizontal = getPaddingLeft() + getPaddingRight() + margins.leftMargin + margins.rightMargin;
                int vertical = getPaddingTop() + getPaddingBottom() + margins.topMargin + margins.bottomMargin;
                child.measure(getChildMeasureSpec(widthSpec, horizontal + widthUsed, margins.width),
                        getChildMeasureSpec(heightSpec, vertical + heightUsed, margins.height));
            }

            @Override public boolean onInterceptTouchEvent(MotionEvent event) {
                return activeTab != LOG_TAB && super.onInterceptTouchEvent(event);
            }
        };
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
        body.setPadding(dp(16), dp(12), dp(16), dp(16));
        compact.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(compact);
        smallPreview = new FrameLayout(this);
        smallPreview.setBackground(card(0xff152327, 14));
        smallPreview.setClipToOutline(true);
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
        buildDashboard(body);

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
        pickingHint = new TextView(this);
        pickingHint.setText("点画面选取坐标");
        pickingHint.setTextColor(Color.WHITE);
        pickingHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        pickingHint.setBackground(card(0xdd152327, 10));
        pickingHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hint.topMargin = dp(8);
        fullPreview.addView(pickingHint, hint);
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

    private void buildDashboard(LinearLayout body) {
        LinearLayout tabBar = row(body);
        tabBar.setBackground(card(PALE, 12));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] titles = {"一键挂机", "宏配置", "手动接管", "日志"};
        for (int i = 0; i < titles.length; i++) {
            int tab = i;
            tabs[i] = rowButton(tabBar, titles[i], () -> showTab(tab));
            tabs[i].setMinHeight(dp(42));
            tabs[i].setPadding(dp(4), 0, dp(4), 0);
        }
        tabContent = column(body);
        tabContent.setPadding(0, dp(10), 0, dp(8));
        for (int i = 0; i < panels.length; i++) panels[i] = column(tabContent);

        LinearLayout taskSplit = row(panels[TASK_TAB]);
        taskSplit.setMinimumHeight(dp(156));
        LinearLayout taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        taskSplit.addView(taskList, new LinearLayout.LayoutParams(0, -1, 5));
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(12), dp(8), dp(12), dp(8));
        settings.setBackground(card(Color.WHITE, 12));
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(0, -1, 7);
        settingsSize.setMarginStart(dp(8));
        taskSplit.addView(settings, settingsSize);
        LinearLayout relicRow = row(taskList);
        LinearLayout macroRow = row(taskList);
        relicTask = taskCheck(relicRow, "识别核桃", "relic_task_enabled", true);
        macroTask = taskCheck(macroRow, "技能宏", "macro_task_enabled", false);
        relicLabel = taskLabel(relicRow, "识别核桃", () -> selectTask(false));
        macroLabel = taskLabel(macroRow, "技能宏", () -> selectTask(true));
        relicSettings = column(settings);
        TextView relicLimitLabel = Screen.text(relicSettings, "开核桃数");
        relicLimit = new EditText(this);
        relicLimit.setId(View.generateViewId());
        relicLimitLabel.setLabelFor(relicLimit.getId());
        relicLimit.setSingleLine();
        relicLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        relicLimit.setText(String.valueOf(getPreferences(MODE_PRIVATE).getInt("relic_limit", 0)));
        relicSettings.addView(relicLimit, new LinearLayout.LayoutParams(-1, -2));
        Screen.text(relicSettings, "0 表示不限");
        macroSettings = column(settings);
        Screen.text(macroSettings, "使用的宏");
        macroPicker = new Spinner(this, Spinner.MODE_DROPDOWN);
        macroPicker.setContentDescription("使用的宏");
        macroSettings.addView(macroPicker, new LinearLayout.LayoutParams(-1, dp(42)));
        Screen.button(macroSettings, "编辑宏", () -> showTab(MACRO_TAB));
        selectTask(false);

        macroEditor = new MacroEditor(panels[MACRO_TAB], this::pickPoint, this::refreshMacroPicker, this::macroDeleted);
        refreshMacroPicker(getPreferences(MODE_PRIVATE).getInt("selected_macro", 0));
        macroPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == macroPicker.getSelectedItemPosition() && position < macroEditor.macros().size()) {
                    getPreferences(MODE_PRIVATE).edit().putInt("selected_macro", position).apply();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout takeoverPanel = panels[TAKEOVER_TAB];
        takeoverButton = Screen.button(takeoverPanel, "开始接管", this::toggleTakeover);
        takeoverButton.setTextColor(Color.WHITE);
        takeoverButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        gamepad = new VirtualGamepadView(this, new VirtualGamepadView.Listener() {
            @Override public void key(int code, boolean pressed) {
                call(service -> service.manualGamepadKey(code, pressed));
            }

            @Override public void stick(boolean right, float x, float y) {
                call(service -> service.manualGamepadStick(right, x, y));
            }

            @Override public void release() { call(ISurvivalSession::releaseManualGamepad); }
        });
        LinearLayout.LayoutParams padSize = new LinearLayout.LayoutParams(-1, -2);
        padSize.topMargin = dp(8);
        takeoverPanel.addView(gamepad, padSize);

        LinearLayout logPanel = panels[LOG_TAB];
        logPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        logPanel.setPadding(dp(12), dp(8), dp(12), dp(12));
        logPanel.setBackground(card(Color.WHITE, 12));
        LinearLayout logHeader = row(logPanel);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = Screen.text(logHeader, "本次日志");
        logTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Screen.button(logHeader, "历史日志", this::showArchivedLogs);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logPanel.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout logContent = new LinearLayout(this);
        logContent.setOrientation(LinearLayout.VERTICAL);
        logScroll.addView(logContent, new ScrollView.LayoutParams(-1, -2));
        taskLog = Screen.text(logContent, "暂无任务日志");
        taskLog.setTextIsSelectable(true);

        quickPanel = column(body);
        quickPanel.setPadding(dp(8), dp(4), dp(8), dp(4));
        quickPanel.setBackground(card(PALE, 12));
        quickPanel.setVisibility(View.GONE);
        authorize = Screen.button(quickPanel, "连接 Shizuku", () -> gameConnection.connect(true));
        LinearLayout gameControls = row(quickPanel);
        launch = rowButton(gameControls, "启动游戏", () -> call(ISurvivalSession::launch));
        close = rowButton(gameControls, "关闭游戏", () -> call(ISurvivalSession::closeGame));
        LinearLayout feedback = row(quickPanel);
        vibration = rowButton(feedback, "关闭震动", () -> toggleFeedback(0));
        audio = rowButton(feedback, "关闭声音", () -> toggleFeedback(1));
        LinearLayout displayControls = row(quickPanel);
        screenshot = rowButton(displayControls, "截图", this::saveScreenshot);
        lockScreen = rowButton(displayControls, "锁屏", () -> call(ISurvivalSession::lockScreen));
        battery = Screen.button(quickPanel, "请求电池优化豁免", this::requestBatteryExemption);
        notifications = Screen.button(quickPanel, "开启后台运行通知",
                () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS));
        footer = row(body);
        footer.setPadding(0, dp(8), 0, 0);
        start = rowButton(footer, "开始任务", this::startSurvival);
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        start.setTextColor(Color.WHITE);
        quickOptions = rowButton(footer, "快捷选项", this::toggleQuickOptions);
        showTab(TASK_TAB);
    }

    private CheckBox taskCheck(LinearLayout row, String title, String preference, boolean fallback) {
        CheckBox box = new CheckBox(this);
        box.setContentDescription("启用" + title);
        box.setChecked(getPreferences(MODE_PRIVATE).getBoolean(preference, fallback));
        row.addView(box, new LinearLayout.LayoutParams(dp(38), dp(48)));
        box.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean(preference, checked).apply();
            selectTask(button == macroTask);
            updateControls();
        });
        return box;
    }

    private TextView taskLabel(LinearLayout row, String title, Runnable click) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(INK);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(4), 0, dp(4), 0);
        label.setOnClickListener(view -> click.run());
        row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
        return label;
    }

    private void selectTask(boolean selectMacro) {
        relicSettings.setVisibility(selectMacro ? View.GONE : View.VISIBLE);
        macroSettings.setVisibility(selectMacro ? View.VISIBLE : View.GONE);
        relicLabel.setBackground(selectMacro ? null : card(PALE, 8));
        macroLabel.setBackground(selectMacro ? card(PALE, 8) : null);
    }

    private void showTab(int tab) {
        activeTab = tab;
        tabContent.setLayoutParams(new LinearLayout.LayoutParams(-1, tab == LOG_TAB ? 0 : -2, 1));
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            tabs[i].setBackground(card(i == tab ? ACCENT : PALE, 9));
            tabs[i].setTextColor(i == tab ? Color.WHITE : INK);
            tabs[i].setSelected(i == tab);
        }
        footer.setVisibility(tab == TASK_TAB ? View.VISIBLE : View.GONE);
        if (tab != TASK_TAB) {
            quickPanel.setVisibility(View.GONE);
            quickOptions.setText("快捷选项");
        }
        updateTakeoverControls();
        if (tab == LOG_TAB) {
            compact.scrollTo(0, 0);
            refreshTaskLog(null);
            poll();
        }
    }

    private void toggleQuickOptions() {
        boolean show = quickPanel.getVisibility() != View.VISIBLE;
        quickPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        quickOptions.setText(show ? "收起选项" : "快捷选项");
        if (show) {
            readFeedback();
            updateBackgroundOptions();
            quickOptions.post(() -> compact.smoothScrollTo(0, footer.getBottom()));
        }
    }

    private void toggleTakeover() {
        ISurvivalSession active = session;
        if (active == null || takeoverSwitching) return;
        boolean enable = !takeoverActive;
        takeoverSwitching = true;
        updateTakeoverControls();
        statusWorker.execute(() -> {
            try {
                boolean accepted = active.setManualTakeover(enable);
                boolean current = active.manualTakeover();
                String status = active.status();
                ui.post(() -> {
                    if (session != active) return;
                    takeoverActive = current;
                    takeoverSwitching = false;
                    showStatus(status);
                    updateControls();
                    if (!accepted) Toast.makeText(this, enable ? "暂时无法接管游戏" : "释放手柄输入失败，请重试",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (RemoteException error) {
                ui.post(() -> {
                    if (session == active) gameConnection.disconnected("Shizuku 用户服务已断开");
                });
            } catch (RuntimeException error) {
                ui.post(() -> {
                    if (session != active) return;
                    takeoverSwitching = false;
                    showStatus("手动接管切换失败 · " + error.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void refreshMacroPicker(int index) {
        int requested = index >= 0 ? index : macroPicker.getSelectedItemPosition();
        List<String> names = new ArrayList<>();
        for (SkillMacro macro : macroEditor.macros()) names.add(macro.name());
        MacroEditor.setItems(macroPicker, names.isEmpty() ? List.of("暂无已保存的宏") : names);
        int selected = Math.max(0, Math.min(requested, names.size() - 1));
        macroPicker.setSelection(selected);
        getPreferences(MODE_PRIVATE).edit().putInt("selected_macro", selected).apply();
        if (start != null) updateControls();
    }

    private void macroDeleted(int removed) {
        int selected = macroPicker.getSelectedItemPosition();
        refreshMacroPicker(Math.max(0, selected > removed ? selected - 1 : selected));
    }

    private GradientDrawable card(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void startSurvival() {
        if (phase == VirtualGameSession.RUNNING) {
            call(ISurvivalSession::stopSurvival);
            return;
        }
        boolean relicEnabled = relicTask.isChecked();
        String text = relicLimit.getText().toString().trim();
        int limit;
        try {
            limit = !relicEnabled || text.isEmpty() ? 0 : Integer.parseInt(text);
            if (limit < 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            selectTask(false);
            relicLimit.setError("请输入有效的非负整数");
            return;
        }
        if (relicEnabled) {
            getPreferences(MODE_PRIVATE).edit().putInt("relic_limit", limit).apply();
            relicLimit.setText(String.valueOf(limit));
        }
        String macroJson = "";
        if (macroTask.isChecked()) {
            int selected = macroPicker.getSelectedItemPosition();
            if (selected < 0 || selected >= macroEditor.macros().size()) {
                Toast.makeText(this, "请先保存一个技能宏", Toast.LENGTH_SHORT).show();
                showTab(MACRO_TAB);
                return;
            }
            try {
                macroJson = macroEditor.macros().get(selected).toJson().toString();
            } catch (org.json.JSONException error) {
                showStatus("读取宏失败 · " + error.getMessage());
                return;
            }
            getPreferences(MODE_PRIVATE).edit().putInt("selected_macro", selected).apply();
        }
        relicLimit.clearFocus();
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.hide(WindowInsets.Type.ime());
        BackgroundTaskService.start(this, relicEnabled, limit, macroJson);
    }

    private void setExpanded(boolean show) {
        if (expanded == show || (show && !hasGameFrame)) return;
        Consumer<Point> cancelledPick = show ? null : pointReceiver;
        if (!show) pointReceiver = null;
        endGesture();
        expanded = show;
        if (show) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeExpandedPreview);
            smallPreview.removeView(preview);
            preview.setContentDescription("可操作的 Warframe 画面");
            fullPreview.addView(preview, 0, new FrameLayout.LayoutParams(-1, -1));
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeExpandedPreview);
            fullPreview.removeView(preview);
            preview.setContentDescription("Warframe 预览，轻触放大");
            smallPreview.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        }
        compact.setVisibility(show ? View.GONE : View.VISIBLE);
        updateTakeoverControls();
        fullPreview.setVisibility(show ? View.VISIBLE : View.GONE);
        applySystemBars();
        setRequestedOrientation(show ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        pickingHint.setVisibility(show && pointReceiver != null ? View.VISIBLE : View.GONE);
        if (cancelledPick != null) cancelledPick.accept(null);
    }

    private boolean pickPoint(Consumer<Point> result) {
        if (!hasGameFrame) {
            Toast.makeText(this, "请先启动游戏，或直接填写坐标", Toast.LENGTH_SHORT).show();
            return false;
        }
        pointReceiver = result;
        setExpanded(true);
        return true;
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
        if (pointReceiver != null) {
            if (action == MotionEvent.ACTION_UP) {
                Rect bounds = GpuPreview.fit(preview.getWidth(), preview.getHeight(),
                        VirtualGameSession.WIDTH, VirtualGameSession.HEIGHT);
                if (bounds.contains((int) event.getX(), (int) event.getY())) {
                    Point point = new Point((int) ((event.getX() - bounds.left)
                            * VirtualGameSession.WIDTH / bounds.width()), (int) ((event.getY() - bounds.top)
                            * VirtualGameSession.HEIGHT / bounds.height()));
                    Consumer<Point> result = pointReceiver;
                    pointReceiver = null;
                    setExpanded(false);
                    result.accept(point);
                }
            }
            return true;
        }
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
        boolean readLog = activeTab == LOG_TAB;
        statusWorker.execute(() -> {
            try {
                int currentPhase = active.phase();
                boolean takingOver = active.manualTakeover();
                String message = active.status();
                boolean frameAvailable = currentPhase != VirtualGameSession.CLOSED && active.hasFrame();
                String log = readLog ? active.taskLog() : null;
                ui.post(() -> {
                    if (session != active || !visible) return;
                    phase = currentPhase;
                    takeoverActive = takingOver;
                    if (showStatus(message) && expanded && message.startsWith("操作失败")) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                    if (hasGameFrame != frameAvailable) {
                        hasGameFrame = frameAvailable;
                        preview.setAlpha(frameAvailable ? 1 : 0);
                    }
                    if (log != null) refreshTaskLog(log);
                    updateControls();
                });
            } catch (RemoteException error) {
                ui.post(() -> {
                    if (session == active) gameConnection.disconnected("Shizuku 用户服务已断开");
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

    private void readFeedback() {
        updateFeedback(0, null);
        updateFeedback(1, null);
    }

    private void toggleFeedback(int type) {
        Boolean current = feedbackMuted[type];
        updateFeedback(type, current == null ? null : !current);
    }

    private void updateFeedback(int type, Boolean muted) {
        ISurvivalSession active = session;
        if (active == null || feedbackBusy[type]) return;
        feedbackBusy[type] = true;
        updateControls();
        statusWorker.execute(() -> {
            try {
                boolean result = type == 0
                        ? (muted == null ? active.isGameVibrationMuted() : active.setGameVibrationMuted(muted))
                        : (muted == null ? active.isGameAudioMuted() : active.setGameAudioMuted(muted));
                ui.post(() -> {
                    if (session != active) return;
                    feedbackBusy[type] = false;
                    feedbackMuted[type] = result;
                    updateControls();
                });
            } catch (RemoteException | RuntimeException error) {
                ui.post(() -> {
                    if (session != active) return;
                    feedbackBusy[type] = false;
                    feedbackMuted[type] = null;
                    updateControls();
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void requestBatteryExemption() {
        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(request);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "请在手机系统的电池优化设置中允许本应用", Toast.LENGTH_LONG).show();
        }
    }

    private void updateBackgroundOptions() {
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        battery.setText(exempt ? "电池优化已豁免" : "请求电池优化豁免");
        battery.setEnabled(!exempt);
        notifications.setVisibility(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED ? View.GONE : View.VISIBLE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATIONS) updateBackgroundOptions();
    }

    private void refreshTaskLog(String remoteLog) {
        String content = taskHistory.read();
        if (content.isEmpty()) content = remoteLog == null || remoteLog.isEmpty() ? "暂无任务日志" : remoteLog;
        if (!content.contentEquals(taskLog.getText())) taskLog.setText(content);
    }

    private void showArchivedLogs() {
        File[] files = taskHistory.archives();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("历史日志");
        if (files.length == 0) {
            dialog.setMessage("暂无历史日志").setPositiveButton("关闭", null).show();
            return;
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        dialog.setItems(names, (ignored, index) -> openArchivedLog(files[index]))
                .setNegativeButton("关闭", null).show();
    }

    private void openArchivedLog(File file) {
        try {
            ScrollView scroll = new ScrollView(this);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(20), 0, dp(20), 0);
            scroll.addView(body);
            Screen.text(body, taskHistory.readArchive(file)).setTextIsSelectable(true);
            new AlertDialog.Builder(this).setTitle(file.getName()).setView(scroll)
                    .setPositiveButton("关闭", null).show();
        } catch (IOException error) {
            Toast.makeText(this, "读取历史日志失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectionChanged(ISurvivalSession active, String message) {
        if (session != active) {
            takeoverActive = false;
            takeoverSwitching = false;
            session = active;
            touching = hasGameFrame = false;
            phase = VirtualGameSession.CLOSED;
            preview.setAlpha(0);
            for (int i = 0; i < feedbackBusy.length; i++) {
                feedbackBusy[i] = false;
                feedbackMuted[i] = null;
            }
            if (active != null) {
                updatePreviewSurface();
                if (quickPanel.getVisibility() == View.VISIBLE) readFeedback();
                poll();
            }
        }
        if (message != null) showStatus(message);
        if (activeTab == LOG_TAB) refreshTaskLog(null);
        updateControls();
    }

    private void updateControls() {
        boolean connected = session != null;
        boolean hasFrame = connected && hasGameFrame;
        boolean running = phase == VirtualGameSession.RUNNING;
        authorize.setEnabled(!gameConnection.bound());
        launch.setEnabled(connected && phase == VirtualGameSession.CLOSED);
        close.setEnabled(connected && phase != VirtualGameSession.CLOSED);
        start.setEnabled(connected && (running || phase == VirtualGameSession.READY && !takeoverActive
                && !takeoverSwitching
                && !gameConnection.hasBackgroundTask() && (relicTask.isChecked() || macroTask.isChecked())));
        start.setAlpha(start.isEnabled() ? 1 : 0.55f);
        start.setText(running ? "停止任务" : "开始任务");
        relicTask.setEnabled(!running);
        macroTask.setEnabled(!running);
        relicLimit.setEnabled(!running);
        macroPicker.setEnabled(!running && !macroEditor.macros().isEmpty());
        screenshot.setEnabled(hasFrame && !saving);
        lockScreen.setEnabled(connected);
        updateTakeoverControls();
        feedbackButton(vibration, 0, "震动", connected);
        feedbackButton(audio, 1, "声音", connected);
    }

    private void updateTakeoverControls() {
        boolean ready = session != null && (phase == VirtualGameSession.READY || phase == VirtualGameSession.RUNNING);
        takeoverButton.setEnabled(ready && !takeoverSwitching);
        takeoverButton.setAlpha(takeoverButton.isEnabled() ? 1 : 0.55f);
        takeoverButton.setText(takeoverSwitching ? "正在切换…" : takeoverActive ? "停止接管" : "开始接管");
        gamepad.setInteractive(ready && takeoverActive && !takeoverSwitching && visible && !expanded
                && activeTab == TAKEOVER_TAB);
    }

    private void feedbackButton(Button button, int type, String name, boolean connected) {
        button.setEnabled(connected && !feedbackBusy[type]);
        button.setText(feedbackBusy[type] ? "正在更新" + name
                : feedbackMuted[type] == null ? "读取" + name
                : (feedbackMuted[type] ? "恢复" : "关闭") + name);
    }

    private void call(SessionAction action) {
        if (session == null) return;
        try {
            action.run(session);
        } catch (RemoteException error) {
            gameConnection.disconnected("Shizuku 用户服务已断开");
        }
    }

    private int dp(int value) { return Screen.dp(this, value); }

    private LinearLayout row(LinearLayout body) {
        LinearLayout row = new LinearLayout(this);
        body.addView(row);
        return row;
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(-1, -2));
        return column;
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
