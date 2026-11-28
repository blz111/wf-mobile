package dev.wfassistant.mobile;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class MacroEditor {
    private static final int INK = 0xff17352e;
    private static final int SECONDARY = 0xff506960;
    private static final int ACCENT = 0xff216958;
    private static final int BORDER = 0xffd9e6e0;
    private static final int TINT = 0xffe6f2ec;
    private static final int SURFACE = 0xfff2f7f4;

    interface PointPicker {
        boolean open(Consumer<Point> result);
    }

    private final Context context;
    private final PointPicker pointPicker;
    private final IntConsumer onSaved, onDeleted;
    private final Button picker, saveButton, exportButton, deleteButton;
    private final TextView actionCount;
    private final EditText name;
    private final LinearLayout steps;
    private final List<SkillMacro.Action> draft = new ArrayList<>();
    private List<SkillMacro> macros;
    private int editing = -1;
    private final boolean loadFailed;

    MacroEditor(LinearLayout panel, PointPicker pointPicker, IntConsumer onSaved, IntConsumer onDeleted) {
        context = panel.getContext();
        this.pointPicker = pointPicker;
        this.onSaved = onSaved;
        this.onDeleted = onDeleted;
        String loadError = null;
        try {
            macros = SkillMacro.load(context);
        } catch (Exception error) {
            macros = List.of();
            loadError = "无法读取已保存的宏 · " + error.getMessage();
        }
        loadFailed = loadError != null;

        LinearLayout library = section(panel, 0);
        LinearLayout tools = row(library, 0);
        tools.addView(text("宏库", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button importButton = button("导入 JSON", TINT, ACCENT, this::showImport);
        tools.addView(importButton, new LinearLayout.LayoutParams(-2, dp(48)));
        exportButton = button("导出 JSON", SURFACE, ACCENT, this::export);
        addTrailing(tools, exportButton);
        importButton.setEnabled(!loadFailed);
        LinearLayout selection = row(library, 8);
        picker = button("", SURFACE, INK, this::selectMacro);
        picker.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        picker.setSingleLine(true);
        picker.setEllipsize(TextUtils.TruncateAt.END);
        selection.addView(picker, new LinearLayout.LayoutParams(0, dp(48), 1));
        addTrailing(selection, button("＋ 新建", TINT, ACCENT, () -> chooseMacro(-1)));
        if (loadError != null) library.addView(text(loadError, 13, SECONDARY, false));

        LinearLayout editor = section(panel, 12);
        LinearLayout editorHeader = row(editor, 0);
        TextView nameLabel = text("宏名称", 18, INK, true);
        editorHeader.addView(nameLabel, new LinearLayout.LayoutParams(0, -2, 1));
        deleteButton = button("删除宏", 0xfffcebe8, 0xffa12f23, this::confirmDelete);
        editorHeader.addView(deleteButton, new LinearLayout.LayoutParams(-2, dp(48)));
        saveButton = button("保存宏", ACCENT, Color.WHITE, this::save);
        addTrailing(editorHeader, saveButton);
        name = new EditText(context);
        name.setId(View.generateViewId());
        nameLabel.setLabelFor(name.getId());
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setTextSize(16);
        name.setTextColor(INK);
        name.setHintTextColor(SECONDARY);
        name.setHint("输入宏名称");
        name.setPadding(dp(12), 0, dp(12), 0);
        name.setBackground(outline(SURFACE, 10, BORDER));
        editor.addView(name, spaced(-1, dp(48), 6));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateActions(); }
            @Override public void afterTextChanged(Editable editable) {}
        });
        LinearLayout actions = row(editor, 16);
        actions.addView(text("动作序列", 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        actionCount = text("", 13, SECONDARY, false);
        actions.addView(actionCount);
        steps = column(editor);
        editor.addView(button("＋ 添加动作", TINT, ACCENT, this::selectAction), spaced(-1, dp(48), 10));
        edit(macros.isEmpty() ? -1 : 0);
    }

    List<SkillMacro> macros() { return macros; }

    private void selectAction() {
        SkillMacro.Kind[] kinds = {SkillMacro.Kind.TAP, SkillMacro.Kind.HOLD, SkillMacro.Kind.WAIT,
                SkillMacro.Kind.GAMEPAD_DOWN, SkillMacro.Kind.GAMEPAD_UP,
                SkillMacro.Kind.JOYSTICK_MOVE, SkillMacro.Kind.JOYSTICK_CENTER};
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) labels[i] = kinds[i].label;
        new AlertDialog.Builder(context).setTitle("添加动作")
                .setItems(labels, (dialog, index) -> showAction(kinds[index], -1, null))
                .setNegativeButton("取消", null).show();
    }

    private void selectMacro() {
        String[] names = macros.stream().map(SkillMacro::name).toArray(String[]::new);
        new AlertDialog.Builder(context).setTitle("选择已保存的宏")
                .setSingleChoiceItems(names, editing, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != editing) chooseMacro(which);
                }).setNegativeButton("取消", null).show();
    }

    private void chooseMacro(int selected) {
        if (!hasChanges()) {
            edit(selected);
            return;
        }
        new AlertDialog.Builder(context).setTitle("当前宏有未保存的修改")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (dialog, which) -> edit(selected)).show();
    }

    private boolean hasChanges() {
        if (editing < 0) return !name.getText().toString().isBlank() || !draft.isEmpty();
        SkillMacro saved = macros.get(editing);
        return !saved.name().equals(name.getText().toString().trim()) || !saved.actions().equals(draft);
    }

    private void showSelection() {
        picker.setEnabled(!macros.isEmpty());
        picker.setText(editing >= 0 ? macros.get(editing).name() + "  ▾"
                : (macros.isEmpty() ? "暂无已保存的宏" : "选择已保存的宏  ▾"));
    }

    private void edit(int selected) {
        editing = selected;
        SkillMacro macro = selected < 0 ? null : macros.get(selected);
        name.setText(macro == null ? "" : macro.name());
        draft.clear();
        if (macro != null) draft.addAll(macro.actions());
        showSelection();
        renderSteps();
    }

    private void renderSteps() {
        steps.removeAllViews();
        actionCount.setText(draft.size() + " 步");
        if (draft.isEmpty()) {
            TextView empty = text("暂无动作", 14, SECONDARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(outline(SURFACE, 10, BORDER));
            steps.addView(empty, spaced(-1, dp(68), 10));
        }
        for (int i = 0; i < draft.size(); i++) {
            int index = i;
            SkillMacro.Action action = draft.get(i);
            LinearLayout step = row(steps, 8);
            step.setBackground(outline(SURFACE, 10, BORDER));
            step.setPadding(dp(8), 0, dp(2), 0);
            TextView order = text(String.valueOf(i + 1), 13, ACCENT, true);
            order.setGravity(Gravity.CENTER);
            step.addView(order, new LinearLayout.LayoutParams(dp(30), dp(48)));
            LinearLayout details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(7), dp(9), dp(2), dp(9));
            details.setBackground(ripple(Color.TRANSPARENT, 8));
            details.setFocusable(true);
            details.setOnClickListener(view -> showAction(action.kind(), index, action));
            details.setContentDescription("编辑第 " + (i + 1) + " 步，" + action.describe());
            step.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            details.addView(text(action.kind().label, 15, INK, true));
            details.addView(text(action.detail(), 13, SECONDARY, false));
            TextView more = text("⋮", 22, ACCENT, true);
            more.setGravity(Gravity.CENTER);
            more.setBackground(ripple(Color.TRANSPARENT, 8));
            more.setFocusable(true);
            more.setContentDescription("第 " + (i + 1) + " 步的选项");
            more.setOnClickListener(view -> showStepMenu(view, index));
            step.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        updateActions();
    }

    private void showStepMenu(View anchor, int index) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add("上移").setEnabled(index > 0).setOnMenuItemClickListener(item -> {
            move(index, index - 1);
            return true;
        });
        menu.getMenu().add("下移").setEnabled(index < draft.size() - 1).setOnMenuItemClickListener(item -> {
            move(index, index + 1);
            return true;
        });
        menu.getMenu().add("删除动作").setOnMenuItemClickListener(item -> {
            draft.remove(index);
            renderSteps();
            return true;
        });
        menu.show();
    }

    private void move(int from, int to) {
        if (to < 0 || to >= draft.size()) return;
        draft.add(to, draft.remove(from));
        renderSteps();
    }

    private void showAction(SkillMacro.Kind kind, int index, SkillMacro.Action action) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int margin = Screen.dp(context, 20);
        form.setPadding(margin, 0, margin, 0);
        boolean touch = kind == SkillMacro.Kind.TAP || kind == SkillMacro.Kind.HOLD;
        LinearLayout coordinates = touch ? row(form, 6) : null;
        EditText x = touch ? field(fieldColumn(coordinates, 0), "横坐标 X", action == null ? "" : "" + action.x()) : null;
        EditText y = touch ? field(fieldColumn(coordinates, 10), "纵坐标 Y", action == null ? "" : "" + action.y()) : null;
        if (touch) {
            x.setHint("0–1279");
            y.setHint("0–719");
        }
        Spinner keys = kind.isKey() ? choice(form, "按键", List.of(SkillMacro.GAMEPAD_LABELS),
                action == null ? 0 : SkillMacro.keyIndex(action.key())) : null;
        boolean joystick = kind.isStick();
        LinearLayout axes = joystick ? row(form, 6) : null;
        Spinner stick = joystick ? choice(fieldColumn(axes, 0), "摇杆", List.of(SkillMacro.Stick.values()),
                action == null ? 0 : action.stick().ordinal()) : null;
        Spinner direction = joystick && !kind.isRelease() ? choice(fieldColumn(axes, 10), "方向", List.of(SkillMacro.Direction.values()),
                action == null ? 0 : action.direction().ordinal()) : null;
        int defaultTime = switch (kind) {
            case TAP -> 45;
            case GAMEPAD -> 100;
            case WAIT, HOLD, JOYSTICK -> 500;
            case GAMEPAD_DOWN, GAMEPAD_UP, JOYSTICK_MOVE, JOYSTICK_CENTER -> 0;
        };
        EditText duration = kind == SkillMacro.Kind.TAP || kind.isInstant() ? null : field(form,
                kind == SkillMacro.Kind.WAIT ? "等待时间（毫秒）" : joystick ? "推住时间（毫秒）" : "按住时间（毫秒）",
                String.valueOf(action == null ? defaultTime : action.milliseconds()));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(kind.label).setView(form)
                .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        if (touch) form.addView(button("从游戏画面取点", TINT, ACCENT, () -> {
            if (pointPicker.open(point -> {
                dialog.show();
                if (point != null) {
                    x.setText(String.valueOf(point.x));
                    y.setText(String.valueOf(point.y));
                }
            })) dialog.hide();
        }), spaced(-1, dp(48), 10));
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int milliseconds = duration == null
                        ? (action == null ? defaultTime : action.milliseconds()) : number(duration);
                SkillMacro.Action updated = joystick
                        ? new SkillMacro.Action(kind, 0, 0, 0, milliseconds, (SkillMacro.Stick) stick.getSelectedItem(),
                                direction == null ? null : (SkillMacro.Direction) direction.getSelectedItem())
                        : new SkillMacro.Action(kind, x == null ? 0 : number(x), y == null ? 0 : number(y),
                                keys == null ? 0 : SkillMacro.GAMEPAD_KEYS[keys.getSelectedItemPosition()], milliseconds);
                if (index < 0) draft.add(updated);
                else draft.set(index, updated);
                renderSteps();
                dialog.dismiss();
            } catch (NumberFormatException error) {
                toast("请填写坐标或毫秒数");
            } catch (IllegalArgumentException error) {
                toast(error.getMessage());
            }
        }));
        dialog.show();
    }

    private void showImport() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);
        TextView hint = text("可粘贴单个宏对象，或包含多个宏的数组。", 14, SECONDARY, false);
        form.addView(hint);
        EditText json = new EditText(context);
        json.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        json.setTypeface(Typeface.MONOSPACE);
        json.setTextSize(13);
        json.setMinLines(5);
        json.setMaxLines(8);
        json.setGravity(Gravity.TOP | Gravity.START);
        json.setPadding(dp(12), dp(10), dp(12), dp(10));
        json.setBackground(outline(SURFACE, 12, BORDER));
        json.setHint("{\"name\": \"宏名称\", \"actions\": […]}");
        json.setContentDescription("宏 JSON");
        form.addView(json, spaced(-1, -2, 10));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("导入 JSON").setView(form)
                .setNeutralButton("粘贴", null).setNegativeButton("取消", null).setPositiveButton("导入", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                ClipData clip = clipboard.getPrimaryClip();
                CharSequence pasted = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
                if (pasted == null) {
                    toast("剪贴板没有文本");
                    return;
                }
                json.setText(pasted);
                json.setSelection(json.length());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    String content = json.getText().toString();
                    if (content.isBlank()) throw new IllegalArgumentException("请粘贴要导入的 JSON");
                    importMacros(SkillMacro.parseImport(content));
                    dialog.dismiss();
                } catch (Exception error) {
                    json.setError(error.getMessage());
                    json.requestFocus();
                }
            });
        });
        dialog.show();
    }

    private void importMacros(List<SkillMacro> imported) throws Exception {
        boolean keepDraft = hasChanges();
        int first = macros.size();
        List<SkillMacro> updated = new ArrayList<>(macros);
        Set<String> names = new HashSet<>();
        for (SkillMacro macro : macros) names.add(macro.name());
        for (SkillMacro macro : imported) {
            String unique = macro.name();
            for (int suffix = 2; !names.add(unique); suffix++) unique = macro.name() + " (" + suffix + ")";
            updated.add(unique.equals(macro.name()) ? macro : new SkillMacro(unique, macro.actions()));
        }
        SkillMacro.save(context, updated);
        macros = updated;
        if (keepDraft) showSelection();
        else edit(first);
        onSaved.accept(keepDraft ? -1 : first);
        toast("已导入 " + imported.size() + " 个宏");
    }

    private void export() {
        try {
            SkillMacro macro = new SkillMacro(name.getText().toString(), draft);
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(macro.name() + " · JSON", macro.toJson().toString(2)));
        } catch (Exception error) {
            toast("导出失败 · " + error.getMessage());
        }
    }

    private void updateActions() {
        boolean complete = !name.getText().toString().isBlank()
                && draft.stream().anyMatch(action -> action.kind() != SkillMacro.Kind.WAIT);
        saveButton.setEnabled(!loadFailed && complete && hasChanges());
        exportButton.setEnabled(complete);
        deleteButton.setEnabled(!loadFailed && editing >= 0);
    }

    private void confirmDelete() {
        if (loadFailed || editing < 0) return;
        int selected = editing;
        new AlertDialog.Builder(context).setTitle("删除宏")
                .setMessage("删除「" + macros.get(selected).name() + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> delete(selected)).show();
    }

    private void delete(int selected) {
        try {
            List<SkillMacro> updated = new ArrayList<>(macros);
            updated.remove(selected);
            SkillMacro.save(context, updated);
            macros = updated;
            edit(Math.min(selected, macros.size() - 1));
            onDeleted.accept(selected);
            toast("已删除宏");
        } catch (Exception error) {
            toast("删除失败 · " + error.getMessage());
        }
    }

    private void save() {
        if (loadFailed) return;
        try {
            SkillMacro macro = new SkillMacro(name.getText().toString(), draft);
            List<SkillMacro> updated = new ArrayList<>(macros);
            int target = editing < 0 ? updated.size() : editing;
            for (int i = 0; i < updated.size(); i++) {
                if (i != target && macro.name().equals(updated.get(i).name())) {
                    throw new IllegalArgumentException("已有同名宏");
                }
            }
            if (editing < 0) updated.add(macro);
            else updated.set(editing, macro);
            SkillMacro.save(context, updated);
            macros = updated;
            edit(target);
            onSaved.accept(target);
            toast("已保存宏");
        } catch (Exception error) {
            toast("保存失败 · " + error.getMessage());
        }
    }

    private void toast(String message) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }

    private static int number(EditText field) {
        return Integer.parseInt(field.getText().toString().trim());
    }

    private EditText field(LinearLayout parent, String label, String value) {
        EditText input = new EditText(parent.getContext());
        input.setId(View.generateViewId());
        TextView caption = text(label, 13, SECONDARY, true);
        caption.setLabelFor(input.getId());
        parent.addView(caption, spaced(-1, -2, 9));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(value);
        input.setTextSize(16);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(outline(SURFACE, 10, BORDER));
        parent.addView(input, spaced(-1, dp(48), 5));
        return input;
    }

    private static LinearLayout column(LinearLayout parent) {
        LinearLayout column = new LinearLayout(parent.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private Spinner choice(LinearLayout parent, String label, List<?> items, int selected) {
        Spinner spinner = new Spinner(context, Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());
        TextView caption = text(label, 13, SECONDARY, true);
        caption.setLabelFor(spinner.getId());
        parent.addView(caption, spaced(-1, -2, 9));
        setItems(spinner, items);
        spinner.setSelection(selected);
        parent.addView(spinner, spaced(-1, dp(48), 5));
        return spinner;
    }

    private LinearLayout section(LinearLayout parent, int gap) {
        LinearLayout section = column(parent);
        section.setLayoutParams(spaced(-1, -2, gap));
        section.setPadding(dp(14), dp(12), dp(14), dp(14));
        section.setBackground(outline(Color.WHITE, 16, BORDER));
        return section;
    }

    private LinearLayout row(LinearLayout parent, int gap) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, spaced(-1, -2, gap));
        return row;
    }

    private LinearLayout fieldColumn(LinearLayout row, int gap) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(dp(gap));
        row.addView(column, size);
        return column;
    }

    private void addTrailing(LinearLayout row, Button button) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(48));
        size.setMarginStart(dp(8));
        row.addView(button, size);
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, height);
        size.topMargin = dp(gap);
        return size;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private Button button(String value, int fill, int foreground, Runnable click) {
        Button button = new Button(context);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{fill == ACCENT ? 0xffbdd9d0 : SECONDARY, foreground}));
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(ripple(fill, 10));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x24216958),
                outline(fill, radius, Color.TRANSPARENT), outline(Color.WHITE, radius, Color.TRANSPARENT));
    }

    private GradientDrawable outline(int fill, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) bg.setStroke(dp(1), stroke);
        return bg;
    }

    private int dp(int value) { return Screen.dp(context, value); }

    static <T> void setItems(Spinner spinner, List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }
}
