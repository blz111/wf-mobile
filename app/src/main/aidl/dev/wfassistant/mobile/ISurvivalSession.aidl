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
    // First element is the sequence number of the first returned entry.
    String[] taskLogSince(long after) = 16;
    boolean manualTakeover() = 17;
    boolean setManualTakeover(boolean enabled) = 18;
    void manualGamepadKey(int keyCode, boolean pressed) = 19;
    void manualGamepadStick(boolean right, float x, float y) = 20;
    void releaseManualGamepad() = 21;
    void destroy() = 16777114;
}
