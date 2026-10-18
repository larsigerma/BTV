package me.ashell.localadb;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** GPU-only latest-frame bridge; frame pacing happens before encoding, never by dropping H.264 references. */
final class AdbVideoSurface implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface input;
    private int textureId, program;
    private volatile boolean available;
    private boolean hasImage;
    private final float[] transform = new float[16];
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    AdbVideoSurface(Surface encoder, int width, int height, Handler callbacks) {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            check(EGL14.eglInitialize(display, version, 0, version, 1), "EGL initialize");
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            check(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE},
                    0, configs, 0, 1, count, 0) && count[0] > 0, "EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            check(context != EGL14.EGL_NO_CONTEXT, "EGL context");
            window = EGL14.eglCreateWindowSurface(display, configs[0], encoder, new int[]{EGL14.EGL_NONE}, 0);
            check(window != EGL14.EGL_NO_SURFACE, "EGL window");
            check(EGL14.eglMakeCurrent(display, window, window, context), "EGL current");
            program = createProgram();
            int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); textureId = ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId);
            texture.setDefaultBufferSize(width, height);
            texture.setOnFrameAvailableListener(ignored -> available = true, callbacks);
            input = new Surface(texture);
            vertices.put(new float[]{-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1}).position(0);
            GLES20.glViewport(0, 0, width, height);
        } catch (RuntimeException error) { close(); throw error; }
    }

    Surface input() { return input; }
    boolean hasImage() { return hasImage; }

    boolean draw(long presentationNanos, boolean refresh) {
        if (available) {
            available = false;
            texture.updateTexImage();
            texture.getTransformMatrix(transform);
            hasImage = true;
        } else if (!refresh || !hasImage) return false;
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0);
        int position = GLES20.glGetAttribLocation(program, "position");
        int uv = GLES20.glGetAttribLocation(program, "uv");
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        check(EGLExt.eglPresentationTimeANDROID(display, window, presentationNanos), "EGL timestamp");
        check(EGL14.eglSwapBuffers(display, window), "EGL submit");
        return true;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
            throw new IllegalStateException("ADB shader: " + error);
        }
        return shader;
    }

    private static int createProgram() {
        int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; uniform mat4 transform;"
                + "varying vec2 coord; void main(){gl_Position=vec4(position,0.0,1.0); coord=(transform*vec4(uv,0.0,1.0)).xy;}");
        int fragment = 0, result = 0;
        try {
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float; uniform samplerExternalOES image; varying vec2 coord;"
                    + "void main(){gl_FragColor=texture2D(image,coord);}");
            result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment); GLES20.glLinkProgram(result);
            int[] status = new int[1]; GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
            check(status[0] != 0, "ADB shader link");
            return result;
        } catch (RuntimeException error) {
            if (result != 0) GLES20.glDeleteProgram(result);
            throw error;
        } finally {
            GLES20.glDeleteShader(vertex);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
        }
    }

    private static void check(boolean condition, String operation) {
        if (!condition) throw new IllegalStateException(operation + " failed");
    }

    @Override public void close() {
        if (input != null) { input.release(); input = null; }
        if (texture != null) { texture.release(); texture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, window, window, context);
                if (program != 0) GLES20.glDeleteProgram(program);
                if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE;
    }
}
