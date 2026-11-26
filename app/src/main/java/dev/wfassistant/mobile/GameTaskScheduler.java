package dev.wfassistant.mobile;

import android.graphics.Bitmap;
import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

final class GameTaskScheduler {
    private static final int MONITOR_INTERVAL_MS = 5_000;
    static final int INTERACTION_INTERVAL_MS = 300;

    enum State { NORMAL, SELECTING_RELIC }
    enum Decision { WAIT, CHECK_SOON, WAIT_FOR_RELEASE, RETURN_TO_NORMAL, FINISHED }
    enum TapResult { SENT, MANUAL_TOUCH_ACTIVE, REJECTED }

    record Step(Decision decision, String status, int delayMs) {
        Step(Decision decision, String status) {
            this(decision, status, INTERACTION_INTERVAL_MS);
        }
    }
    record Tick(String status, int delayMs, boolean finished) {}

    @FunctionalInterface
    interface Tap {
        TapResult whenIdle(String operation, Point point) throws Exception;
    }

    interface Task {
        State state();
        Step run(State state, Bitmap frame, Tap tap) throws Exception;
        String progress();
        default void pause() throws Exception {}
    }

    private final List<Task> tasks;
    private final SurvivalRecognition recognition = new SurvivalRecognition();
    private State state = State.NORMAL;
    private Task screenOwner;
    private boolean waitingForRelease;

    GameTaskScheduler(List<Task> selectedTasks) {
        tasks = new ArrayList<>(selectedTasks);
    }

    boolean waitingForRelease() {
        return waitingForRelease;
    }

    State state() { return state; }

    Tick runFrame(Bitmap frame, Tap tap) throws Exception {
        waitingForRelease = false;
        if (screenOwner == null && frame != null) {
            state = recognition.relicSelection(frame) || recognition.relicConfirmation(frame)
                    ? State.SELECTING_RELIC : State.NORMAL;
            if (state != State.NORMAL) pauseNormalTasks();
            for (Task task : tasks) {
                if (state == State.NORMAL || task.state() != state) continue;
                screenOwner = task;
                break;
            }
        }

        String status = state != State.NORMAL && screenOwner == null ? "选核桃页面 · 等待返回普通状态" : null;
        int delay = tasks.stream().anyMatch(task -> task.state() == State.NORMAL)
                ? INTERACTION_INTERVAL_MS : MONITOR_INTERVAL_MS;
        List<Task> scheduled = screenOwner != null ? List.of(screenOwner)
                : state == State.NORMAL ? List.copyOf(tasks) : List.of();
        for (Task task : scheduled) {
            Step step = task.run(state, frame, tap);
            if (step.status() != null) status = status == null ? step.status() : status + " · " + step.status();
            switch (step.decision()) {
                case CHECK_SOON -> delay = Math.min(delay, step.delayMs());
                case WAIT_FOR_RELEASE -> {
                    waitingForRelease = true;
                    delay = INTERACTION_INTERVAL_MS;
                }
                case RETURN_TO_NORMAL -> {
                    state = State.NORMAL;
                    screenOwner = null;
                }
                case FINISHED -> {
                    tasks.remove(task);
                    if (screenOwner == task) {
                        state = State.NORMAL;
                        screenOwner = null;
                    }
                }
                case WAIT -> {}
            }
        }
        return new Tick(status, delay, tasks.isEmpty());
    }

    void pauseNormalTasks() throws Exception {
        for (Task task : tasks) if (task.state() == State.NORMAL) task.pause();
    }

    String stopMessage() {
        String result = "任务已停止";
        for (Task task : tasks) result += " · " + task.progress();
        return result;
    }
}
