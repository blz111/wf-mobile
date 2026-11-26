package dev.wfassistant.mobile;

import android.content.Context;
import android.util.AtomicFile;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

record SkillMacro(String name, List<Action> actions) {
    enum Kind {
        TAP("触屏点按"), HOLD("触屏长按"), WAIT("等待"),
        GAMEPAD_DOWN("手柄按键按下"), GAMEPAD_UP("手柄按键松开"),
        JOYSTICK_MOVE("推摇杆"), JOYSTICK_CENTER("松开摇杆"),
        GAMEPAD("手柄按键（定时）"), JOYSTICK("手柄摇杆（定时）");

        final String label;
        Kind(String label) { this.label = label; }

        boolean isKey() { return this == GAMEPAD || this == GAMEPAD_DOWN || this == GAMEPAD_UP; }
        boolean isStick() { return this == JOYSTICK || this == JOYSTICK_MOVE || this == JOYSTICK_CENTER; }
        boolean isRelease() { return this == GAMEPAD_UP || this == JOYSTICK_CENTER; }
        boolean isInstant() { return this == GAMEPAD_DOWN || this == JOYSTICK_MOVE || isRelease(); }
    }

    enum Stick {
        LEFT("左摇杆"), RIGHT("右摇杆");

        final String label;
        Stick(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum Direction {
        UP("上推", 0, -1), DOWN("下推", 0, 1), LEFT("左推", -1, 0), RIGHT("右推", 1, 0);

        final String label;
        final float x, y;

        Direction(String label, float x, float y) {
            this.label = label;
            this.x = x;
            this.y = y;
        }

        @Override public String toString() { return label; }
    }

    static final int[] GAMEPAD_KEYS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT
    };
    static final String[] GAMEPAD_LABELS = {
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3",
            "方向上", "方向下", "方向左", "方向右", "Start", "Select"
    };

    record Action(Kind kind, int x, int y, int key, int milliseconds, Stick stick, Direction direction) {
        Action(Kind kind, int x, int y, int key, int milliseconds) {
            this(kind, x, y, key, milliseconds, null, null);
        }

        Action {
            if (kind.isInstant() ? milliseconds != 0
                    : milliseconds < 0 || (kind != Kind.WAIT && milliseconds == 0)) {
                throw new IllegalArgumentException("请填写有效的毫秒数");
            }
            if ((kind == Kind.TAP || kind == Kind.HOLD)
                    && (x < 0 || x >= VirtualGameSession.WIDTH || y < 0 || y >= VirtualGameSession.HEIGHT)) {
                throw new IllegalArgumentException("触屏坐标必须在游戏画面内");
            }
            if (kind.isKey() && keyIndex(key) < 0) throw new IllegalArgumentException("未知手柄键");
            if (kind.isStick() && (stick == null || (kind != Kind.JOYSTICK_CENTER && direction == null))) {
                throw new IllegalArgumentException("请选择摇杆和方向");
            }
        }

        String describe() {
            return kind.label + " · " + detail();
        }

        String detail() {
            String duration = milliseconds + " 毫秒";
            return switch (kind) {
                case TAP -> "(" + x + ", " + y + ")";
                case HOLD -> "(" + x + ", " + y + ") · " + duration;
                case WAIT -> duration;
                case GAMEPAD -> GAMEPAD_LABELS[keyIndex(key)] + " · " + duration;
                case JOYSTICK -> stick.label + direction.label + " · " + duration;
                case GAMEPAD_DOWN, GAMEPAD_UP -> GAMEPAD_LABELS[keyIndex(key)];
                case JOYSTICK_MOVE -> stick.label + direction.label;
                case JOYSTICK_CENTER -> stick.label;
            };
        }
    }

    SkillMacro {
        name = name.trim();
        actions = List.copyOf(actions);
        if (name.isEmpty() || actions.isEmpty()) throw new IllegalArgumentException("请填写宏名称并添加动作");
        if (actions.stream().allMatch(action -> action.kind() == Kind.WAIT)) {
            throw new IllegalArgumentException("宏至少需要一个触屏或手柄动作");
        }
    }

    static int keyIndex(int key) {
        for (int i = 0; i < GAMEPAD_KEYS.length; i++) if (GAMEPAD_KEYS[i] == key) return i;
        return -1;
    }

    JSONObject toJson() throws JSONException {
        JSONArray steps = new JSONArray();
        for (Action action : actions) {
            JSONObject step = new JSONObject().put("type", action.kind().name());
            if (!action.kind().isInstant()) step.put("ms", action.milliseconds());
            switch (action.kind()) {
                case TAP, HOLD -> step.put("x", action.x()).put("y", action.y());
                case GAMEPAD, GAMEPAD_DOWN, GAMEPAD_UP -> step.put("key", KeyEvent.keyCodeToString(action.key()));
                case JOYSTICK, JOYSTICK_MOVE ->
                        step.put("stick", action.stick().name()).put("direction", action.direction().name());
                case JOYSTICK_CENTER -> step.put("stick", action.stick().name());
                case WAIT -> {}
            }
            steps.put(step);
        }
        return new JSONObject().put("name", name).put("actions", steps);
    }

    static SkillMacro fromJson(JSONObject json) throws JSONException {
        JSONArray steps = json.getJSONArray("actions");
        List<Action> decoded = new ArrayList<>();
        for (int i = 0; i < steps.length(); i++) {
            try {
                JSONObject step = steps.getJSONObject(i);
                Kind kind = enumValue(step, "type", Kind.class);
                int duration = kind.isInstant() && !step.has("ms") ? 0 : integer(step, "ms");
                decoded.add(switch (kind) {
                    case TAP, HOLD -> new Action(kind, integer(step, "x"), integer(step, "y"), 0, duration);
                    case WAIT -> new Action(kind, 0, 0, 0, duration);
                    case GAMEPAD, GAMEPAD_DOWN, GAMEPAD_UP ->
                            new Action(kind, 0, 0, KeyEvent.keyCodeFromString(string(step, "key")), duration);
                    case JOYSTICK, JOYSTICK_MOVE, JOYSTICK_CENTER ->
                            new Action(kind, 0, 0, 0, duration, enumValue(step, "stick", Stick.class),
                                    kind == Kind.JOYSTICK_CENTER ? null : enumValue(step, "direction", Direction.class));
                });
            } catch (JSONException | IllegalArgumentException error) {
                throw new JSONException("第 " + (i + 1) + " 步 · " + error.getMessage());
            }
        }
        return new SkillMacro(string(json, "name"), decoded);
    }

    static List<SkillMacro> parseImport(String content) throws JSONException {
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        JSONTokener reader = new JSONTokener(content);
        Object root = reader.nextValue();
        if (reader.nextClean() != 0) throw new JSONException("JSON 末尾有多余内容");
        if (root instanceof JSONObject macro) return List.of(fromJson(macro));
        if (root instanceof JSONArray macros && macros.length() > 0) return decodeArray(macros);
        throw new JSONException("需要一个宏对象或一个包含宏的数组");
    }

    private static String string(JSONObject json, String field) throws JSONException {
        if (json.get(field) instanceof String value) return value;
        throw new JSONException(field + " 必须是文本");
    }

    private static int integer(JSONObject json, String field) throws JSONException {
        if (json.get(field) instanceof Number value && value.doubleValue() == value.intValue()) {
            return value.intValue();
        }
        throw new JSONException(field + " 必须是整数");
    }

    private static <T extends Enum<T>> T enumValue(JSONObject json, String field, Class<T> type) throws JSONException {
        String value = string(json, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new JSONException(field + " 不支持 " + value);
        }
    }

    private static List<SkillMacro> decodeArray(JSONArray saved) throws JSONException {
        List<SkillMacro> macros = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            try {
                macros.add(fromJson(saved.getJSONObject(i)));
            } catch (JSONException | IllegalArgumentException error) {
                throw new JSONException("第 " + (i + 1) + " 个宏 · " + error.getMessage());
            }
        }
        return macros;
    }

    static List<SkillMacro> load(Context context) throws IOException, JSONException {
        byte[] bytes;
        try {
            bytes = file(context).readFully();
        } catch (FileNotFoundException missing) {
            return new ArrayList<>();
        }
        return decodeArray(new JSONArray(new String(bytes, StandardCharsets.UTF_8)));
    }

    static void save(Context context, List<SkillMacro> macros) throws IOException, JSONException {
        JSONArray saved = new JSONArray();
        for (SkillMacro macro : macros) saved.put(macro.toJson());
        byte[] content = saved.toString(2).getBytes(StandardCharsets.UTF_8);
        AtomicFile file = file(context);
        FileOutputStream output = file.startWrite();
        try {
            output.write(content);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "skill-macros.json"));
    }
}
