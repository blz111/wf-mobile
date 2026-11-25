package dev.wfassistant.mobile;

import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Method;

final class VirtualDisplayPower {
    private final int displayId;
    private final Object power;
    private final Method userActivity, isInteractive, wake;

    VirtualDisplayPower(int displayId) throws ReflectiveOperationException {
        if (displayId <= Display.DEFAULT_DISPLAY) throw new IllegalArgumentException("需要独立虚拟屏");
        Class<?> displayManager = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = displayManager.getMethod("getInstance").invoke(null);
        Object info = displayManager.getMethod("getDisplayInfo", int.class).invoke(global, displayId);
        if (info == null || info.getClass().getField("displayGroupId").getInt(info) <= 0) {
            throw new IllegalStateException("虚拟屏没有独立电源组");
        }

        this.displayId = displayId;
        Class<?> services = Class.forName("android.os.ServiceManager");
        Object binder = services.getMethod("getService", String.class).invoke(null, "power");
        Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
        power = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (power == null) throw new IllegalStateException("电源服务不可用");
        Class<?> api = Class.forName("android.os.IPowerManager");
        userActivity = api.getMethod("userActivity", int.class, long.class, int.class, int.class);
        Method wakeWithId = null;
        Method displayInteractive = null;
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                displayInteractive = api.getMethod("isDisplayInteractive", int.class);
                wakeWithId = api.getMethod("wakeUpWithDisplayId",
                        long.class, int.class, String.class, String.class, int.class);
            } catch (NoSuchMethodException ignored) {
                // A device can support display activity without per-display wake-up.
            }
        }
        isInteractive = displayInteractive;
        wake = wakeWithId;
    }

    void keepAwake() throws ReflectiveOperationException {
        long now = SystemClock.uptimeMillis();
        if (wake != null && Boolean.FALSE.equals(isInteractive.invoke(power, displayId))) {
            wake.invoke(power, now, 2, "warframe:virtual-display", "com.android.shell", displayId);
        }
        userActivity.invoke(power, displayId, now, 0, 0);
    }
}
