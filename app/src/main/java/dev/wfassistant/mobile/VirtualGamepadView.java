package dev.wfassistant.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

final class VirtualGamepadView extends View {
    interface Listener {
        void key(int code, boolean pressed);
        void stick(boolean right, float x, float y);
        void release();
    }

    private static final int WIDTH = 360, HEIGHT = 284;
    private static final float STICK_Y = 207, STICK_RADIUS = 41, DEAD_ZONE = 0.12f;
    private static final float LEFT_X = 96, RIGHT_X = 264;
    private static final int SURFACE = 0xff17332e, PAD = 0xff284b43, OUTLINE = 0xff50786b;
    private static final int ACTIVE = 0xff9fe3c7, MUTED_TEXT = 0xffbbd2ca;
    private static final PadKey[] KEYS = {
            new PadKey(KeyEvent.KEYCODE_BUTTON_L2, "L2", 38, 30, 51, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_L1, "L1", 96, 30, 51, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_R1, "R1", 264, 30, 51, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_R2, "R2", 322, 30, 51, 34),
            new PadKey(KeyEvent.KEYCODE_DPAD_UP, "▲", 72, 72, 34, 34),
            new PadKey(KeyEvent.KEYCODE_DPAD_LEFT, "◀", 33, 111, 34, 34),
            new PadKey(KeyEvent.KEYCODE_DPAD_RIGHT, "▶", 111, 111, 34, 34),
            new PadKey(KeyEvent.KEYCODE_DPAD_DOWN, "▼", 72, 150, 34, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_Y, "Y", 288, 72, 34, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_X, "X", 249, 111, 34, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_B, "B", 327, 111, 34, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_A, "A", 288, 150, 34, 34),
            new PadKey(KeyEvent.KEYCODE_BUTTON_SELECT, "Select", 153, 112, 42, 32),
            new PadKey(KeyEvent.KEYCODE_BUTTON_START, "Start", 207, 112, 42, 32),
            new PadKey(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3", 96, 266, 46, 29),
            new PadKey(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3", 264, 266, 46, 29)
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final Listener listener;

    VirtualGamepadView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setContentDescription("虚拟手柄，含双摇杆、方向键、功能键和肩键");
        setEnabled(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, resolveSize(Math.round(width * (float) HEIGHT / WIDTH), heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / (float) WIDTH, getHeight() / (float) HEIGHT);
        paint.setColor(SURFACE);
        canvas.drawRoundRect(0, 0, WIDTH, HEIGHT, 15, 15, paint);
        for (PadKey key : KEYS) drawKey(canvas, key);
        drawStick(canvas, false);
        drawStick(canvas, true);
        canvas.restore();
    }

    private void drawKey(Canvas canvas, PadKey key) {
        boolean held = heldKey(key) != null;
        paint.setColor(held ? ACTIVE : PAD);
        canvas.drawRoundRect(key.x - key.width / 2, key.y - key.height / 2,
                key.x + key.width / 2, key.y + key.height / 2, 9, 9, paint);
        label(canvas, key.label, key.x, key.y, key.label.length() > 2 ? 10 : 14,
                held ? SURFACE : isEnabled() ? Color.WHITE : MUTED_TEXT);
    }

    private void drawStick(Canvas canvas, boolean right) {
        float x = right ? RIGHT_X : LEFT_X;
        Touch active = heldStick(right);
        paint.setColor(OUTLINE);
        canvas.drawCircle(x, STICK_Y, STICK_RADIUS, paint);
        paint.setColor(PAD);
        canvas.drawCircle(x, STICK_Y, STICK_RADIUS - 2, paint);
        float dx = active == null ? 0 : active.x * (STICK_RADIUS - 18);
        float dy = active == null ? 0 : active.y * (STICK_RADIUS - 18);
        paint.setColor(active == null ? OUTLINE : ACTIVE);
        canvas.drawCircle(x + dx, STICK_Y + dy, 17, paint);
        label(canvas, right ? "右" : "左", x + dx, STICK_Y + dy, 12,
                active == null ? Color.WHITE : SURFACE);
    }

    private void label(Canvas canvas, String text, float x, float y, float size, int color) {
        paint.setColor(color);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int actionIndex = event.getActionIndex();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                float x = event.getX(actionIndex) * WIDTH / getWidth();
                float y = event.getY(actionIndex) * HEIGHT / getHeight();
                boolean captured = press(event.getPointerId(actionIndex), x, y);
                if (!captured && event.getActionMasked() == MotionEvent.ACTION_DOWN) return false;
                if (captured) getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Touch touch = touches.get(event.getPointerId(i));
                    if (touch != null && touch.key == null) {
                        moveStick(touch, event.getX(i) * WIDTH / getWidth(), event.getY(i) * HEIGHT / getHeight());
                    }
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(actionIndex));
                if (touches.size() == 0) getParent().requestDisallowInterceptTouchEvent(false);
            }
            case MotionEvent.ACTION_CANCEL -> releaseAll();
        }
        invalidate();
        return true;
    }

    private boolean press(int pointer, float x, float y) {
        for (PadKey key : KEYS) {
            if (Math.abs(x - key.x) <= key.width / 2 && Math.abs(y - key.y) <= key.height / 2) {
                if (heldKey(key) != null) return false;
                touches.put(pointer, new Touch(key, false));
                listener.key(key.code, true);
                return true;
            }
        }
        boolean right = x >= WIDTH / 2f;
        if (Math.hypot(x - (right ? RIGHT_X : LEFT_X), y - STICK_Y) > STICK_RADIUS || heldStick(right) != null) {
            return false;
        }
        Touch touch = new Touch(null, right);
        touches.put(pointer, touch);
        moveStick(touch, x, y);
        return true;
    }

    private void moveStick(Touch touch, float x, float y) {
        float dx = (x - (touch.right ? RIGHT_X : LEFT_X)) / (STICK_RADIUS - 9);
        float dy = (y - STICK_Y) / (STICK_RADIUS - 9);
        float magnitude = (float) Math.hypot(dx, dy);
        float scale = magnitude <= DEAD_ZONE ? 0 : Math.min(1, (magnitude - DEAD_ZONE) / (1 - DEAD_ZONE)) / magnitude;
        float nextX = scale == 0 ? 0 : dx * scale, nextY = scale == 0 ? 0 : dy * scale;
        if (Math.abs(nextX - touch.x) < 0.02f && Math.abs(nextY - touch.y) < 0.02f
                && (scale != 0 || (touch.x == 0 && touch.y == 0))) return;
        touch.x = nextX;
        touch.y = nextY;
        listener.stick(touch.right, nextX, nextY);
    }

    private void release(int pointer) {
        Touch touch = touches.get(pointer);
        if (touch == null) return;
        touches.remove(pointer);
        if (touch.key != null) listener.key(touch.key.code, false);
        else listener.stick(touch.right, 0, 0);
    }

    void releaseAll() {
        if (touches.size() == 0) return;
        touches.clear();
        listener.release();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    void setInteractive(boolean enabled) {
        if (enabled == isEnabled()) return;
        if (!enabled) releaseAll();
        setEnabled(enabled);
        invalidate();
    }

    private Touch heldKey(PadKey key) {
        for (int i = 0; i < touches.size(); i++) if (touches.valueAt(i).key == key) return touches.valueAt(i);
        return null;
    }

    private Touch heldStick(boolean right) {
        for (int i = 0; i < touches.size(); i++) {
            Touch touch = touches.valueAt(i);
            if (touch.key == null && touch.right == right) return touch;
        }
        return null;
    }

    private record PadKey(int code, String label, float x, float y, float width, float height) {}

    private static final class Touch {
        final PadKey key;
        final boolean right;
        float x, y;

        Touch(PadKey key, boolean right) {
            this.key = key;
            this.right = right;
        }
    }
}
