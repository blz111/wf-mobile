package dev.wfassistant.mobile;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class GpuPreview {
    private static final String TAG = "WarframePreview";
    private static final String VERTEX_SHADER = """
            attribute vec2 position;
            attribute vec2 uv;
            uniform mat4 textureMatrix;
            varying vec2 sampleUv;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
                sampleUv = (textureMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES frameTexture;
            varying vec2 sampleUv;
            void main() {
                gl_FragColor = vec4(texture2D(frameTexture, sampleUv).rgb, 1.0);
            }
            """;

    private final HandlerThread thread = new HandlerThread("wf-gpu-preview");
    private final Handler handler;
    private final int width, height;
    private final float[] matrix = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{
                    -1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1
            });
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface scratch = EGL14.EGL_NO_SURFACE;
    private EGLSurface output = EGL14.EGL_NO_SURFACE;
    private EGLSurface currentSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture texture;
    private Surface input;
    private int program, position, uv, textureMatrix;
    private int outputWidth, outputHeight;
    private long frameTimestamp, statsStart;
    private int received, presented;
    private boolean closed;
    private volatile boolean hasFrame;

    GpuPreview(int width, int height) throws Exception {
        this.width = width;
        this.height = height;
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            onThread(() -> {
                initialize();
                return null;
            });
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    Surface inputSurface() { return input; }
    boolean hasFrame() { return hasFrame; }

    void attach(Surface surface, int width, int height) throws Exception {
        onThread(() -> {
            detachOutput();
            if (surface == null || closed) return null;
            output = EGL14.eglCreateWindowSurface(display, config, surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            require(output != EGL14.EGL_NO_SURFACE, "连接预览画面");
            current(output);
            EGL14.eglSwapInterval(display, 0);
            outputWidth = width;
            outputHeight = height;
            if (hasFrame) present();
            return null;
        });
    }

    Bitmap screenshot() throws Exception {
        return onThread(() -> {
            if (!hasFrame || closed) return null;
            current(scratch);
            draw(width, height);
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "读取截图");
            byte[] upright = new byte[rgba.capacity()];
            int rowBytes = width * 4;
            for (int row = 0; row < height; row++) {
                rgba.position((height - 1 - row) * rowBytes);
                rgba.get(upright, row * rowBytes, rowBytes);
            }
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            result.copyPixelsFromBuffer(ByteBuffer.wrap(upright));
            return result;
        });
    }

    void close() {
        handler.post(() -> {
            closed = true;
            hasFrame = false;
            if (texture != null) texture.setOnFrameAvailableListener(null);
            if (input != null) input.release();
            if (texture != null) texture.release();
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (output != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, output);
                if (scratch != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, scratch);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            thread.quitSafely();
        });
    }

    static Rect fit(int width, int height, int sourceWidth, int sourceHeight) {
        int contentWidth = Math.min(width, (int) ((long) height * sourceWidth / sourceHeight));
        int contentHeight = Math.min(height, (int) ((long) width * sourceHeight / sourceWidth));
        int left = (width - contentWidth) / 2;
        int top = (height - contentHeight) / 2;
        return new Rect(left, top, left + contentWidth, top + contentHeight);
    }

    private <T> T onThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if (!handler.post(task)) throw new IllegalStateException("预览已关闭");
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            task.cancel(false);
            throw error;
        }
    }

    private void initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1),
                "初始化 EGL");
        int[] attributes = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        };
        EGLConfig[] matches = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, matches, 0, 1, count, 0) && count[0] > 0,
                "选择 EGL 配置");
        config = matches[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "创建 EGL 上下文");
        scratch = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
        require(scratch != EGL14.EGL_NO_SURFACE, "创建截图缓冲");
        current(scratch);

        program = GLES20.glCreateProgram();
        int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        position = GLES20.glGetAttribLocation(program, "position");
        uv = GLES20.glGetAttribLocation(program, "uv");
        textureMatrix = GLES20.glGetUniformLocation(program, "textureMatrix");
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        int target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        GLES20.glBindTexture(target, id[0]);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(id[0]);
        texture.setDefaultBufferSize(width, height);
        texture.setOnFrameAvailableListener(this::onFrame, handler);
        input = new Surface(texture);
    }

    private void onFrame(SurfaceTexture source) {
        if (closed) return;
        try {
            current(output == EGL14.EGL_NO_SURFACE ? scratch : output);
            source.updateTexImage();
            long timestamp = source.getTimestamp();
            if (hasFrame && frameTimestamp == timestamp) return;
            frameTimestamp = timestamp;
            source.getTransformMatrix(matrix);
            hasFrame = true;
            received++;
            if (output != EGL14.EGL_NO_SURFACE) present();
            long now = System.nanoTime();
            if (statsStart == 0) statsStart = now;
            if (now - statsStart >= 3_000_000_000L) {
                double seconds = (now - statsStart) / 1_000_000_000.0;
                Log.i(TAG, "Virtual display " + Math.round(received / seconds)
                        + " fps, GPU preview " + Math.round(presented / seconds) + " fps");
                received = presented = 0;
                statsStart = now;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Preview output failed", error);
            try {
                detachOutput();
            } catch (RuntimeException detachError) {
                Log.w(TAG, "Could not detach preview output", detachError);
            }
        }
    }

    private void present() {
        draw(outputWidth, outputHeight);
        require(EGL14.eglSwapBuffers(display, output), "提交预览帧");
        presented++;
    }

    private void detachOutput() {
        if (output == EGL14.EGL_NO_SURFACE) return;
        EGLSurface detached = output;
        output = EGL14.EGL_NO_SURFACE;
        try {
            current(scratch);
        } finally {
            EGL14.eglDestroySurface(display, detached);
        }
    }

    private void current(EGLSurface surface) {
        if (currentSurface != surface) {
            require(EGL14.eglMakeCurrent(display, surface, surface, context), "绑定 EGL 缓冲");
            currentSurface = surface;
        }
    }

    private void draw(int width, int height) {
        Rect bounds = fit(width, height, this.width, this.height);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(bounds.left, height - bounds.bottom, bounds.width(), bounds.height());
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(textureMatrix, 1, false, matrix, 0);
        quad.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + "失败 · 0x" + Integer.toHexString(EGL14.eglGetError()));
    }
}
