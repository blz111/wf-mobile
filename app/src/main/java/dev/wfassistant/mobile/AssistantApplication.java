package dev.wfassistant.mobile;

import android.app.Application;
import android.content.Context;

import rikka.shizuku.ShizukuProvider;

public final class AssistantApplication extends Application {
    private GameConnection gameConnection;

    static GameConnection connection(Context context) {
        AssistantApplication app = (AssistantApplication) context.getApplicationContext();
        if (app.gameConnection == null) app.gameConnection = new GameConnection(app);
        return app.gameConnection;
    }

    @Override public void onCreate() {
        super.onCreate();
        new TaskHistory(this).recoverInterruptedRun();
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ShizukuProvider.disableAutomaticSuiInitialization();
    }
}
