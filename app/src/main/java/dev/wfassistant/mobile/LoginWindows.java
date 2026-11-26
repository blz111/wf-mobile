package dev.wfassistant.mobile;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

final class LoginWindows {
    private static final ComponentName GOOGLE_SIGN_IN = new ComponentName("com.google.android.gms",
            "com.google.android.gms.auth.api.credentials.assistedsignin.ui.GoogleSignInActivity");
    private static final ComponentName DEFAULT_SELECTOR = new ComponentName("com.android.credentialmanager",
            "com.android.credentialmanager.CredentialSelectorActivity");
    private static final ComponentName OPPO_SELECTOR = new ComponentName("com.android.credentialmanager",
            "com.oplus.credentialmanager.CredentialSelectorActivity");

    static Set<ComponentName> resolve(PackageManager packages) {
        Set<ComponentName> allowed = new HashSet<>();
        allowed.add(GOOGLE_SIGN_IN);

        if (!allowSystemSelector(allowed, packages, configuredSelector("config_oemCredentialManagerDialogComponent"))) {
            allowSystemSelector(allowed, packages, OPPO_SELECTOR);
        }
        if (!allowSystemSelector(allowed, packages, configuredSelector("config_fallbackCredentialManagerDialogComponent"))
                && !allowSystemSelector(allowed, packages, configuredSelector("config_credentialManagerDialogComponent"))) {
            allowSystemSelector(allowed, packages, DEFAULT_SELECTOR);
        }
        Log.i("WarframeDisplay", "Allowed login windows: " + allowed);
        return Set.copyOf(allowed);
    }

    private static ComponentName configuredSelector(String name) {
        Resources system = Resources.getSystem();
        int id = system.getIdentifier(name, "string", "android");
        if (id == 0) return null;
        try {
            return ComponentName.unflattenFromString(system.getString(id).trim());
        } catch (Resources.NotFoundException error) {
            return null;
        }
    }

    private static boolean allowSystemSelector(Set<ComponentName> allowed, PackageManager packages,
                                               ComponentName selector) {
        if (selector == null) return false;
        try {
            ActivityInfo activity = packages.getActivityInfo(selector,
                    PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if (!activity.enabled || !activity.applicationInfo.enabled
                    || (activity.applicationInfo.flags & systemFlags) == 0) return false;
            allowed.add(selector);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }
}
