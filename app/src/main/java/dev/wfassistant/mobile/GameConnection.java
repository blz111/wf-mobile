package dev.wfassistant.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import rikka.shizuku.Shizuku;

final class GameConnection implements ServiceConnection {
    interface Listener {
        void onConnectionChanged(ISurvivalSession session, String message);
    }

    private static final int REQUEST_PERMISSION = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        if (session() == null && bound()) {
            unbind();
            notifyListener("连接 Shizuku 用户服务超时");
        }
    };
    private final Shizuku.UserServiceArgs args;
    private Listener listener;
    private boolean bound, backgroundTask;
    private ISurvivalSession session;

    GameConnection(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, VirtualGameSession.class))
                .processNameSuffix("survival_session").daemon(false).version(23);
        Shizuku.addBinderReceivedListenerSticky(() -> main.post(() -> {
            if (listener != null || backgroundTask) connect(false);
        }));
        Shizuku.addBinderDeadListener(() -> main.post(() -> disconnected("Shizuku 已停止，请重新启动")));
        Shizuku.addRequestPermissionResultListener((request, result) -> {
            if (request == REQUEST_PERMISSION) main.post(() -> {
                if (listener == null) return;
                if (result == PackageManager.PERMISSION_GRANTED) connect(false);
                else notifyListener("请在 Shizuku 中授权本应用");
            });
        });
    }

    ISurvivalSession session() { return session; }
    boolean bound() { return bound; }
    boolean hasBackgroundTask() { return backgroundTask; }

    void attach(Listener listener) {
        this.listener = listener;
        notifyListener(null);
        connect(false);
    }

    void detach(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
            unbindIfUnused();
        }
    }

    boolean beginBackgroundTask() {
        if (backgroundTask || session == null) return false;
        backgroundTask = true;
        notifyListener(null);
        return true;
    }

    void endBackgroundTask() {
        backgroundTask = false;
        unbindIfUnused();
        notifyListener(null);
    }

    void connect(boolean interactive) {
        if (bound) return;
        if (!Shizuku.pingBinder()) {
            notifyListener("请先启动 Shizuku");
            return;
        }
        try {
            if (Shizuku.getUid() != VirtualGameSession.SHELL_UID || Shizuku.getVersion() < 13) {
                notifyListener("请通过无线调试或 ADB 启动 Shizuku 13 或以上版本");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                notifyListener("等待 Shizuku 授权");
                if (interactive) Shizuku.requestPermission(REQUEST_PERMISSION);
                return;
            }
            bound = true;
            notifyListener("正在连接 Shizuku 用户服务…");
            Shizuku.bindUserService(args, this);
            main.postDelayed(timeout, 10_000);
        } catch (RuntimeException error) {
            disconnected("连接失败 · " + error.getMessage());
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        main.post(() -> {
            if (!bound) return;
            main.removeCallbacks(timeout);
            session = ISurvivalSession.Stub.asInterface(binder);
            notifyListener(null);
        });
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        main.post(() -> disconnected("Shizuku 用户服务已断开"));
    }

    void disconnected(String message) {
        // Remove this process's client registration even if the Shizuku Binder has died.
        if (bound) {
            try {
                Shizuku.unbindUserService(args, this, false);
            } catch (RuntimeException ignored) {
                // The connection can disappear during teardown.
            }
        }
        main.removeCallbacks(timeout);
        session = null;
        bound = false;
        notifyListener(message);
    }

    private void unbindIfUnused() {
        if (listener == null && !backgroundTask) unbind();
    }

    private void unbind() {
        if (bound) {
            try {
                Shizuku.unbindUserService(args, this, Shizuku.pingBinder());
            } catch (RuntimeException ignored) {
                // The connection can disappear during teardown.
            }
        }
        main.removeCallbacks(timeout);
        session = null;
        bound = false;
    }

    private void notifyListener(String message) {
        if (listener != null) listener.onConnectionChanged(session, message);
    }
}
