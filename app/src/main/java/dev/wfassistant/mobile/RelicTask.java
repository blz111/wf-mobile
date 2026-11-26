package dev.wfassistant.mobile;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.SystemClock;

import static dev.wfassistant.mobile.GameTaskScheduler.INTERACTION_INTERVAL_MS;

import dev.wfassistant.mobile.GameTaskScheduler.Decision;
import dev.wfassistant.mobile.GameTaskScheduler.State;
import dev.wfassistant.mobile.GameTaskScheduler.Step;
import dev.wfassistant.mobile.GameTaskScheduler.Tap;

final class RelicTask implements GameTaskScheduler.Task {
    private static final int EQUIP_RETRY_MS = 1_000;
    private static final int CONFIRM_TIMEOUT_MS = 4_000;

    private enum Stage { EQUIP, CONFIRM, WAIT_FOR_CLOSE, WAIT_FOR_COUNTED_CLOSE }

    private final SurvivalRecognition recognition = new SurvivalRecognition();
    private final int limit;
    private Stage stage = Stage.EQUIP;
    private long equipped;
    private long nextEquipRetryAt, confirmationDeadline;

    RelicTask(int limit) {
        this.limit = limit;
    }

    @Override public State state() { return State.SELECTING_RELIC; }

    @Override public Step run(State state, Bitmap frame, Tap tap) throws Exception {
        if (state == State.NORMAL) return step(Decision.WAIT, "等待选核桃页面");
        return switch (stage) {
            case EQUIP -> equip(frame, tap);
            case CONFIRM -> confirm(frame, tap);
            case WAIT_FOR_CLOSE, WAIT_FOR_COUNTED_CLOSE -> waitForClose(frame);
        };
    }

    private Step equip(Bitmap frame, Tap tap) throws Exception {
        if (frame == null) return new Step(Decision.CHECK_SOON, null);
        if (recognition.relicConfirmation(frame)) {
            return step(Decision.WAIT, "等待核桃确认框关闭");
        }
        if (!recognition.relicSelection(frame)) {
            return step(Decision.RETURN_TO_NORMAL, "等待选核桃页面");
        }
        return tapEquip(tap, false);
    }

    private Step tapEquip(Tap tap, boolean retry) throws Exception {
        if (!tap.whenIdle(new Point(809, 606))) {
            return step(Decision.WAIT_FOR_RELEASE, "等待松手后装备核桃");
        }
        long now = SystemClock.uptimeMillis();
        nextEquipRetryAt = now + EQUIP_RETRY_MS;
        confirmationDeadline = now + CONFIRM_TIMEOUT_MS;
        stage = Stage.CONFIRM;
        return step(Decision.CHECK_SOON, retry ? "确认框未出现 · 已重试点击装备" : "已点击装备以执行任务 · 等待确认框");
    }

    private Step confirm(Bitmap frame, Tap tap) throws Exception {
        if (frame != null && recognition.relicConfirmation(frame)) {
            if (!tap.whenIdle(new Point(505, 402))) {
                return step(Decision.WAIT_FOR_RELEASE, "等待松手后确认装备核桃");
            }
            stage = Stage.WAIT_FOR_COUNTED_CLOSE;
            return step(Decision.WAIT, "已点击确认装备核桃");
        }
        long now = SystemClock.uptimeMillis();
        if (now < nextEquipRetryAt) {
            return new Step(Decision.CHECK_SOON, null, (int) Math.min(INTERACTION_INTERVAL_MS, nextEquipRetryAt - now));
        }
        if (frame != null && recognition.relicSelection(frame)) return tapEquip(tap, true);
        if (now < confirmationDeadline) return new Step(Decision.CHECK_SOON, null);
        stage = Stage.WAIT_FOR_CLOSE;
        return step(Decision.WAIT, "未识别到核桃确认框 · 等待页面关闭");
    }

    private Step waitForClose(Bitmap frame) {
        if (frame == null || recognition.relicSelection(frame) || recognition.relicConfirmation(frame)) {
            return new Step(Decision.WAIT, null);
        }
        if (stage == Stage.WAIT_FOR_COUNTED_CLOSE) equipped++;
        stage = Stage.EQUIP;
        if (limit > 0 && equipped >= limit) return step(Decision.FINISHED, "已达到开核桃数，停止检测");
        return step(Decision.RETURN_TO_NORMAL, "等待选核桃页面");
    }

    @Override public String progress() {
        return limit == 0 ? "已装备 " + equipped + " 个" : "开核桃 " + equipped + "/" + limit;
    }

    private Step step(Decision decision, String status) {
        return new Step(decision, status + " · " + progress());
    }
}
