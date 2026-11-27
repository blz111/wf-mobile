package dev.wfassistant.mobile;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface ISurvivalSession {
    void launch() = 0;
    String status() = 1;
    boolean hasFrame() = 2;
    int phase() = 3;
    void touch(int action, in int[] ids, in float[] xs, in float[] ys) = 4;
    void closeGame() = 5;
    boolean startSurvival(boolean relicEnabled, int relicLimit, String macroJson) = 6;
    void stopSurvival() = 7;
    boolean saveScreenshot(in ParcelFileDescriptor output) = 8;
    void setPreviewSurface(in Surface surface, int width, int height) = 9;
    boolean isGameVibrationMuted() = 10;
    boolean setGameVibrationMuted(boolean muted) = 11;
    boolean isGameAudioMuted() = 12;
    boolean setGameAudioMuted(boolean muted) = 13;
    String taskLog() = 14;
    void lockScreen() = 15;
    void destroy() = 16777114;
}
