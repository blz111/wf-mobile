package dev.wfassistant.mobile;

import android.graphics.Bitmap;

import dev.wfassistant.mobile.GameTaskScheduler.Decision;
import dev.wfassistant.mobile.GameTaskScheduler.State;
import dev.wfassistant.mobile.GameTaskScheduler.Step;
import dev.wfassistant.mobile.GameTaskScheduler.Tap;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MacroTask implements GameTaskScheduler.Task {
    interface Input {
        boolean isIdle();
        boolean press(SkillMacro.Action action) throws Exception;
        void release(SkillMacro.Action action) throws Exception;
        void resetController() throws Exception;
    }

    private final SkillMacro macro;
    private final ScheduledExecutorService worker;
    private final Input input;
    private final Consumer<String> log;
    private final Consumer<Exception> onFailure;
    private final boolean hasDelay;
    private final Set<Integer> sentKeys = new HashSet<>();
    private ScheduledFuture<?> pending;
    private SkillMacro.Action pressed;
    private int actionIndex;
    private boolean running;
    private boolean loggedJoystick;

    MacroTask(SkillMacro macro, ScheduledExecutorService worker, Input input,
              Consumer<String> log, Consumer<Exception> onFailure) {
        this.macro = macro;
        this.worker = worker;
        this.input = input;
        this.log = log;
        this.onFailure = onFailure;
        hasDelay = macro.actions().stream().anyMatch(action -> action.milliseconds() > 0);
    }

    @Override public State state() { return State.NORMAL; }

    @Override public Step run(State state, Bitmap frame, Tap tap) throws Exception {
        if (state != State.NORMAL || frame == null) {
            pause();
            return new Step(Decision.CHECK_SOON, null);
        }
        if (!running && input.isIdle()) {
            running = true;
            log.accept("执行技能宏 · " + macro.name());
            pending = worker.schedule(this::advance, 0, TimeUnit.MILLISECONDS);
        }
        return new Step(Decision.CHECK_SOON, running ? "普通状态 · 技能宏 " + macro.name()
                : "普通状态 · 等待手动输入结束");
    }

    private void advance() {
        pending = null;
        if (!running) return;
        try {
            releasePressed();
            if (!input.isIdle()) {
                pause();
                return;
            }
            if (actionIndex == macro.actions().size()) {
                input.resetController();
                actionIndex = 0;
            }
            SkillMacro.Action action = macro.actions().get(actionIndex);
            if (action.kind().isRelease()) {
                input.release(action);
            } else if (action.kind() != SkillMacro.Kind.WAIT) {
                if (!input.press(action)) {
                    pause();
                    return;
                }
                if (!action.kind().isInstant()) pressed = action;
                if (action.kind().isKey() && sentKeys.add(action.key())) {
                    log.accept("已提交手柄按键 · " + action.describe());
                }
                if (action.kind().isStick() && !loggedJoystick) {
                    log.accept("已提交手柄摇杆 · " + action.describe());
                    loggedJoystick = true;
                }
            }
            actionIndex++;
            int delay = !hasDelay && actionIndex == macro.actions().size() ? 16 : action.milliseconds();
            pending = worker.schedule(this::advance, delay, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            onFailure.accept(error);
        }
    }

    @Override public void pause() throws Exception {
        if (pending != null) pending.cancel(false);
        pending = null;
        actionIndex = 0;
        if (running) log.accept("暂停技能宏 · " + macro.name());
        running = false;
        try {
            releasePressed();
        } catch (Exception error) {
            try {
                input.resetController();
            } catch (Exception resetError) {
                error.addSuppressed(resetError);
            }
            throw error;
        }
        input.resetController();
    }

    private void releasePressed() throws Exception {
        if (pressed == null) return;
        SkillMacro.Action action = pressed;
        pressed = null;
        input.release(action);
    }

    @Override public String progress() { return "技能宏 " + macro.name(); }
}
