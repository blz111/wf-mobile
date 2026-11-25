package dev.wfassistant.mobile;

import android.app.Application;
import android.content.Context;

import rikka.shizuku.ShizukuProvider;

public final class AssistantApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ShizukuProvider.disableAutomaticSuiInitialization();
    }
}
