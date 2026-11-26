package dev.wfassistant.mobile;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.SystemClock;

import static dev.wfassistant.mobile.GameTaskScheduler.INTERACTION_INTERVAL_MS;

import dev.wfassistant.mobile.GameTaskScheduler.Decision;
import dev.wfassistant.mobile.GameTaskScheduler.State;
import dev.wfassistant.mobile.GameTaskScheduler.Step;
import dev.wfassistant.mobile.GameTaskScheduler.Tap;
import dev.wfassistant.mobile.GameTaskScheduler.TapResult;

final class RelicTask implements GameTaskScheduler.Task {
    private static final int EQUIP_RETRY_MS = 1_000;
    private static final int CONFIRM_TIMEOUT_MS = 4_000;
    // 第一排首个实际遗物，左边是「不装备遗物」。
    private static final int FIRST_RELIC_X = 256, FIRST_RELIC_Y = 185;

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
        return selectOrEquip(frame, tap, false);
    }

    private Step selectOrEquip(Bitmap frame, Tap tap, boolean retryEquip) throws Exception {
        if (!recognition.relicSelectionHint(frame)) return tapEquip(tap, retryEquip);
        String operation = "选择首个遗物";
        TapResult result = tap.whenIdle(operation, new Point(FIRST_RELIC_X, FIRST_RELIC_Y));
        if (result != TapResult.SENT) return retryTap(result, operation);
        stage = Stage.EQUIP;
        return step(Decision.CHECK_SOON, "已点击首个遗物 · 等待选中");
    }

    private Step tapEquip(Tap tap, boolean retry) throws Exception {
        String operation = retry ? "重试装备核桃" : "装备核桃";
        TapResult result = tap.whenIdle(operation, new Point(809, 606));
        if (result == TapResult.MANUAL_TOUCH_ACTIVE) return retryTap(result, operation);
        long now = SystemClock.uptimeMillis();
        nextEquipRetryAt = now + EQUIP_RETRY_MS;
        confirmationDeadline = now + CONFIRM_TIMEOUT_MS;
        stage = Stage.CONFIRM;
        if (result == TapResult.REJECTED) return retryTap(result, operation);
        return step(Decision.CHECK_SOON, retry ? "确认框未出现 · 已重试点击装备" : "已点击装备以执行任务 · 等待确认框");
    }

    private Step confirm(Bitmap frame, Tap tap) throws Exception {
        if (frame != null && recognition.relicConfirmation(frame)) {
            String operation = "确认装备核桃";
            TapResult result = tap.whenIdle(operation, new Point(505, 402));
            if (result != TapResult.SENT) return retryTap(result, operation);
            stage = Stage.WAIT_FOR_COUNTED_CLOSE;
            return step(Decision.WAIT, "已点击确认装备核桃");
        }
        long now = SystemClock.uptimeMillis();
        if (now < nextEquipRetryAt) {
            return new Step(Decision.CHECK_SOON, null, (int) Math.min(INTERACTION_INTERVAL_MS, nextEquipRetryAt - now));
        }
        if (frame != null && recognition.relicSelection(frame)) return selectOrEquip(frame, tap, true);
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

    private Step retryTap(TapResult result, String operation) {
        return result == TapResult.MANUAL_TOUCH_ACTIVE
                ? step(Decision.WAIT_FOR_RELEASE, "等待松手后" + operation)
                : new Step(Decision.CHECK_SOON, null);
    }

    private Step step(Decision decision, String status) {
        return new Step(decision, status + " · " + progress());
    }
}
