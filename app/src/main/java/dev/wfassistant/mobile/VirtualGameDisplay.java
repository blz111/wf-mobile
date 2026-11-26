package dev.wfassistant.mobile;

import android.companion.AssociationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

final class VirtualGameDisplay {
    static final String SHELL_PACKAGE = "com.android.shell";
    // Dedicated locally administered address for this app's shell-owned virtual device.
    private static final String ADDRESS = "02:57:46:4D:4F:42";
    private static final String USER = "0"; // ADB shell belongs to the system user.
    private static final String STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING";
    private static final int ASSOCIATION_TIMEOUT_MS = 10_000;
    private static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;
    private static final int POLICY_TYPE_BLOCKED_ACTIVITY = 6;
    private static final int DEVICE_POLICY_CUSTOM = 1;
    private static final String TAG = "WarframeDisplay";
    private static final String VDM = "android.companion.virtual.VirtualDeviceManager";
    private static final String VDM_INTERFACE = "android.companion.virtual.IVirtualDeviceManager";
    private static final String CDM_INTERFACE = "android.companion.ICompanionDeviceManager";
    private final Object device;
    private final VirtualDisplay display;

    private VirtualGameDisplay(Object device, VirtualDisplay display) {
        this.device = device;
        this.display = display;
    }

    VirtualDisplay display() { return display; }

    static VirtualGameDisplay open(Context shellContext, VirtualDisplayConfig config,
                                   Set<ComponentName> allowed) throws Exception {
        Class<?> managerType = Class.forName(VDM);
        var managerConstructor = managerType.getDeclaredConstructor(Class.forName(VDM_INTERFACE), Context.class);
        managerConstructor.setAccessible(true);
        Object manager = managerConstructor.newInstance(service("virtualdevice", VDM_INTERFACE), shellContext);
        Object companion = service("companiondevice", CDM_INTERFACE);
        Method getAssociations = Class.forName(CDM_INTERFACE).getMethod("getAssociations", String.class, int.class);
        try {
            int associationId = ensureAssociation(companion, getAssociations);
            try {
                return openDevice(manager, associationId, config, allowed, true);
            } catch (SecurityException error) {
                Log.w(TAG, "Could not create an always-unlocked display", error);
                return openDevice(manager, associationId, config, allowed, false);
            }
        } catch (Exception error) {
            removeAssociation();
            throw error;
        }
    }

    private static VirtualGameDisplay openDevice(Object manager, int associationId, VirtualDisplayConfig config,
                                                  Set<ComponentName> allowed, boolean unlocked) throws Exception {
        Class<?> paramsType = Class.forName("android.companion.virtual.VirtualDeviceParams");
        Class<?> builderType = Class.forName(paramsType.getName() + "$Builder");
        Object builder = builderType.getConstructor().newInstance();
        builderType.getMethod("setAllowedActivities", Set.class).invoke(builder, allowed);
        if (unlocked) builderType.getMethod("setLockState", int.class).invoke(builder, LOCK_STATE_ALWAYS_UNLOCKED);
        if (Build.VERSION.SDK_INT >= 36) {
            builderType.getMethod("setDevicePolicy", int.class, int.class)
                    .invoke(builder, POLICY_TYPE_BLOCKED_ACTIVITY, DEVICE_POLICY_CUSTOM);
        }
        Object params = builderType.getMethod("build").invoke(builder);
        Object device = invoke(manager.getClass().getMethod("createVirtualDevice", int.class, paramsType),
                manager, associationId, params);
        if (device == null) throw new IOException("系统未创建虚拟设备");
        try {
            Method createDisplay = device.getClass().getMethod("createVirtualDisplay",
                    VirtualDisplayConfig.class, Executor.class, VirtualDisplay.Callback.class);
            VirtualDisplay display = (VirtualDisplay) invoke(createDisplay, device, config, null, null);
            if (display == null) throw new IOException("系统未创建带白名单的虚拟屏");
            return new VirtualGameDisplay(device, display);
        } catch (Exception error) {
            closeDevice(device);
            throw error;
        }
    }

    private static Object service(String name, String interfaceName) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, name);
        if (binder == null) throw new IOException("系统服务不可用 · " + name);
        return Class.forName(interfaceName + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static int ensureAssociation(Object companion, Method getAssociations) throws Exception {
        int association = findAssociation(companion, getAssociations);
        if (association >= 0) return association;
        if (Build.VERSION.SDK_INT >= 35) {
            VirtualGameSession.command("/system/bin/cmd", "companiondevice", "associate",
                    USER, SHELL_PACKAGE, ADDRESS, STREAMING_PROFILE, "false");
        } else {
            VirtualGameSession.command("/system/bin/cmd", "companiondevice", "associate", USER, SHELL_PACKAGE, ADDRESS);
        }
        long deadline = SystemClock.uptimeMillis() + ASSOCIATION_TIMEOUT_MS;
        do {
            association = findAssociation(companion, getAssociations);
            if (association >= 0) return association;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("等待虚拟设备关联超时");
    }

    private static int findAssociation(Object companion, Method getAssociations) throws Exception {
        List<?> associations = (List<?>) invoke(getAssociations, companion, SHELL_PACKAGE, Integer.parseInt(USER));
        for (Object item : associations) {
            AssociationInfo association = (AssociationInfo) item;
            if (ADDRESS.equalsIgnoreCase(String.valueOf(association.getDeviceMacAddress()))) return association.getId();
        }
        return -1;
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    void close() {
        try {
            display.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not release virtual display", error);
        } finally {
            closeDevice(device);
            removeAssociation();
        }
    }

    private static void closeDevice(Object device) {
        try {
            invoke(device.getClass().getMethod("close"), device);
        } catch (Exception error) {
            Log.w(TAG, "Could not close virtual device", error);
        }
    }

    private static void removeAssociation() {
        try {
            VirtualGameSession.command("/system/bin/cmd", "companiondevice", "disassociate",
                    USER, SHELL_PACKAGE, ADDRESS);
        } catch (Exception error) {
            Log.w(TAG, "Could not remove virtual device association", error);
        }
    }
}
