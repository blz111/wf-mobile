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
import dev.wfassistant.mobile.SurvivalRecognition.Confirmation;

final class RelicTask implements GameTaskScheduler.Task {
    private static final int EQUIP_RETRY_MS = 1_000;
    private static final int CONFIRM_RETRY_MS = 1_000;
    private static final int CONFIRM_TIMEOUT_MS = 4_000;
    // 第一排首个实际遗物，左边是「不装备遗物」。
    private static final int FIRST_RELIC_X = 256, FIRST_RELIC_Y = 185;
    private static final int NO_RELIC_X = 120, NO_RELIC_Y = 185;

    private enum Stage {
        EQUIP, SUBMIT_WITHOUT_RELIC, CONFIRM, DISMISS_CONFIRMATION,
        WAIT_FOR_CLOSE, WAIT_FOR_CONFIRMED_CLOSE
    }

    private final SurvivalRecognition recognition = new SurvivalRecognition();
    private final int limit;
    private Stage stage = Stage.EQUIP;
    private long equipped;
    private long nextEquipRetryAt, nextNoRelicRetryAt, confirmationDeadline, nextConfirmRetryAt, nextDismissRetryAt;

    RelicTask(int limit) {
        this.limit = limit;
    }

    @Override public State state() { return State.SELECTING_RELIC; }

    @Override public Step run(State state, Bitmap frame, Tap tap) throws Exception {
        if (state == State.NORMAL) return step(Decision.WAIT, "等待选核桃页面");
        Confirmation confirmation = recognition.relicConfirmation(frame);
        return switch (stage) {
            case EQUIP -> equip(frame, confirmation, tap);
            case SUBMIT_WITHOUT_RELIC -> submitWithoutRelic(frame, confirmation, tap);
            case CONFIRM -> confirm(frame, confirmation, tap);
            case DISMISS_CONFIRMATION -> dismissConfirmation(frame, confirmation, tap);
            case WAIT_FOR_CLOSE, WAIT_FOR_CONFIRMED_CLOSE -> waitForClose(frame, confirmation, tap);
        };
    }

    private Step equip(Bitmap frame, Confirmation confirmation, Tap tap) throws Exception {
        if (frame == null) return new Step(Decision.CHECK_SOON, null);
        if (confirmation != Confirmation.NONE) {
            return startDismissal(tap);
        }
        if (!recognition.relicSelection(frame)) {
            return step(Decision.RETURN_TO_NORMAL, "等待选核桃页面");
        }
        if (SystemClock.uptimeMillis() < nextEquipRetryAt) return new Step(Decision.CHECK_SOON, null);
        return selectOrEquip(frame, tap, false);
    }

    private Step selectOrEquip(Bitmap frame, Tap tap, boolean retryEquip) throws Exception {
        if (limitReached()) {
            return recognition.noRelicSelected(frame) ? tapEquip(tap, retryEquip) : selectNoRelic(tap);
        }
        if (!recognition.relicSelectionHint(frame)) return tapEquip(tap, retryEquip);
        String operation = "选择首个遗物";
        TapResult result = tap.whenIdle(operation, new Point(FIRST_RELIC_X, FIRST_RELIC_Y));
        if (result != TapResult.SENT) return retryTap(result, operation);
        stage = Stage.EQUIP;
        return step(Decision.CHECK_SOON, "已点击首个遗物 · 等待选中");
    }

    private Step selectNoRelic(Tap tap) throws Exception {
        if (SystemClock.uptimeMillis() < nextNoRelicRetryAt) return new Step(Decision.CHECK_SOON, null);
        String operation = "选择不装备遗物";
        TapResult result = tap.whenIdle(operation, new Point(NO_RELIC_X, NO_RELIC_Y));
        if (result == TapResult.MANUAL_TOUCH_ACTIVE) return retryTap(result, operation);
        long now = SystemClock.uptimeMillis();
        nextNoRelicRetryAt = now + EQUIP_RETRY_MS;
        if (result == TapResult.REJECTED) return retryTap(result, operation);
        stage = Stage.SUBMIT_WITHOUT_RELIC;
        confirmationDeadline = now + CONFIRM_TIMEOUT_MS;
        return step(Decision.CHECK_SOON, "已点击不装备遗物 · 等待选中");
    }

    private Step submitWithoutRelic(Bitmap frame, Confirmation confirmation, Tap tap) throws Exception {
        if (frame == null) return new Step(Decision.CHECK_SOON, null);
        if (confirmation != Confirmation.NONE) return handleConfirmation(confirmation, tap);
        long now = SystemClock.uptimeMillis();
        if (recognition.relicSelection(frame)) {
            if (now < nextEquipRetryAt) return new Step(Decision.CHECK_SOON, null);
            return selectOrEquip(frame, tap, false);
        }
        if (now < confirmationDeadline) return new Step(Decision.CHECK_SOON, null);
        stage = Stage.EQUIP;
        return step(Decision.RETURN_TO_NORMAL, "等待选核桃页面");
    }

    private Step tapEquip(Tap tap, boolean retry) throws Exception {
        String operation = limitReached() ? "提交不装备遗物" : "装备核桃";
        if (retry) operation = "重试" + operation;
        TapResult result = tap.whenIdle(operation, new Point(809, 606));
        if (result == TapResult.MANUAL_TOUCH_ACTIVE) return retryTap(result, operation);
        long now = SystemClock.uptimeMillis();
        nextEquipRetryAt = now + EQUIP_RETRY_MS;
        if (result == TapResult.REJECTED) return retryTap(result, operation);
        confirmationDeadline = now + CONFIRM_TIMEOUT_MS;
        stage = Stage.CONFIRM;
        if (limitReached()) return step(Decision.CHECK_SOON, "已提交不装备遗物 · 等待确认");
        return step(Decision.CHECK_SOON, retry ? "确认框未出现 · 已重试点击装备" : "已点击装备以执行任务 · 等待确认框");
    }

    private Step confirm(Bitmap frame, Confirmation confirmation, Tap tap) throws Exception {
        if (confirmation != Confirmation.NONE) return handleConfirmation(confirmation, tap);
        long now = SystemClock.uptimeMillis();
        if (now < nextEquipRetryAt) {
            return new Step(Decision.CHECK_SOON, null, (int) Math.min(INTERACTION_INTERVAL_MS, nextEquipRetryAt - now));
        }
        if (frame != null && recognition.relicSelection(frame)) return selectOrEquip(frame, tap, true);
        if (now < confirmationDeadline) return new Step(Decision.CHECK_SOON, null);
        stage = Stage.WAIT_FOR_CLOSE;
        return step(Decision.WAIT, "未识别到核桃确认框 · 等待页面关闭");
    }

    private Step handleConfirmation(Confirmation confirmation, Tap tap) throws Exception {
        Confirmation expected = limitReached() ? Confirmation.WITHOUT_RELIC : Confirmation.EQUIP;
        return confirmation == expected ? tapConfirm(tap) : startDismissal(tap);
    }

    private Step tapConfirm(Tap tap) throws Exception {
        long remaining = nextConfirmRetryAt - SystemClock.uptimeMillis();
        if (remaining > 0) {
            return new Step(Decision.CHECK_SOON, null, (int) Math.min(INTERACTION_INTERVAL_MS, remaining));
        }
        boolean retry = stage == Stage.WAIT_FOR_CONFIRMED_CLOSE;
        String operation = limitReached() ? "确认不装备遗物" : "确认装备核桃";
        String attempt = retry ? "重试" + operation : operation;
        TapResult result = tap.whenIdle(attempt, new Point(505, 402));
        if (result == TapResult.MANUAL_TOUCH_ACTIVE) return retryTap(result, attempt);
        nextConfirmRetryAt = SystemClock.uptimeMillis() + CONFIRM_RETRY_MS;
        if (result == TapResult.REJECTED) return retryTap(result, attempt);
        stage = Stage.WAIT_FOR_CONFIRMED_CLOSE;
        return step(Decision.CHECK_SOON, retry ? "确认框仍未关闭 · 已" + attempt : "已点击" + operation);
    }

    private Step startDismissal(Tap tap) throws Exception {
        stage = Stage.DISMISS_CONFIRMATION;
        nextDismissRetryAt = 0;
        return tapNo(tap);
    }

    private Step dismissConfirmation(Bitmap frame, Confirmation confirmation, Tap tap) throws Exception {
        if (frame == null) return new Step(Decision.CHECK_SOON, null);
        if (confirmation != Confirmation.NONE) return tapNo(tap);
        stage = Stage.EQUIP;
        nextEquipRetryAt = 0;
        nextConfirmRetryAt = 0;
        return step(Decision.CHECK_SOON, "核桃确认框已关闭 · 重新检查选核桃页面");
    }

    private Step tapNo(Tap tap) throws Exception {
        long now = SystemClock.uptimeMillis();
        if (now < nextDismissRetryAt) return new Step(Decision.CHECK_SOON, null);
        String operation = "取消意外出现的核桃确认框";
        TapResult result = tap.whenIdle(operation, new Point(774, 402));
        if (result == TapResult.MANUAL_TOUCH_ACTIVE) return retryTap(result, operation);
        nextDismissRetryAt = now + EQUIP_RETRY_MS;
        if (result == TapResult.REJECTED) return retryTap(result, operation);
        return step(Decision.CHECK_SOON, "意外出现核桃确认框 · 已点击否，等待关闭");
    }

    private Step waitForClose(Bitmap frame, Confirmation confirmation, Tap tap) throws Exception {
        boolean awaitingClose = stage == Stage.WAIT_FOR_CONFIRMED_CLOSE;
        if (confirmation != Confirmation.NONE) {
            return awaitingClose ? handleConfirmation(confirmation, tap) : startDismissal(tap);
        }
        if (frame == null || recognition.relicSelection(frame)) {
            return new Step(awaitingClose ? Decision.CHECK_SOON : Decision.WAIT, null);
        }
        boolean counted = awaitingClose && !limitReached();
        if (counted) equipped++;
        stage = Stage.EQUIP;
        nextEquipRetryAt = 0;
        nextNoRelicRetryAt = 0;
        nextConfirmRetryAt = 0;
        if (counted && limitReached()) return step(Decision.RETURN_TO_NORMAL, "已达到开核桃数 · 后续不装备遗物");
        return step(Decision.RETURN_TO_NORMAL, "等待选核桃页面");
    }

    private boolean limitReached() { return limit > 0 && equipped >= limit; }

    @Override public String progress() {
        if (limit == 0) return "已装备 " + equipped + " 个";
        return "开核桃 " + equipped + "/" + limit + (limitReached() ? " · 不装备遗物" : "");
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
