package me.ashell.localadb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/** Shell-UID entry owning a non-captured black layer and its non-interactive white hint. */
public final class AdbPrivacyOverlay {
    private static volatile long heartbeat = SystemClock.elapsedRealtime();

    private AdbPrivacyOverlay() { }

    public static void main(String[] args) {
        Object surface = null;
        Object hintControl = null;
        Surface blackSurface = null;
        Surface hintSurface = null;
        Bitmap hint = null;
        Object transaction = null;
        Class<?> sc = null;
        Class<?> tx = null;
        int stage = 1;
        Thread lease = new Thread(() -> {
            try {
                while (SystemClock.elapsedRealtime() - heartbeat < 10000) Thread.sleep(500);
            } catch (InterruptedException ignored) { return; }
            // Binder death removes the layer even if stdin or SurfaceFlinger calls stop responding.
            System.exit(0);
        }, "ashell-overlay-lease");
        lease.setDaemon(true);
        lease.start();
        try {
            if (android.os.Process.myUid() != 2000 && android.os.Process.myUid() != 0) return;
            if (Build.VERSION.SDK_INT < 30 || System.in.read() != 1) return;
            Looper.prepareMainLooper();
            stage = 2;
            // app_process has no application font initialization. Never shape text or load
            // a font map here: the initialized app sends one bounded, pre-rendered PNG.
            DataInputStream input = new DataInputStream(System.in);
            int size = input.readInt();
            if (size <= 0 || size > AdbPrivacyHint.MAX_BYTES) throw new IOException("Invalid hint size");
            byte[] png = new byte[size];
            input.readFully(png);
            heartbeat = SystemClock.elapsedRealtime();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(png, 0, size, options);
            if (options.outWidth <= 0 || options.outWidth > AdbPrivacyHint.MAX_WIDTH
                    || options.outHeight <= 0 || options.outHeight > AdbPrivacyHint.MAX_HEIGHT)
                throw new IOException("Invalid hint dimensions");
            options.inJustDecodeBounds = false;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            hint = BitmapFactory.decodeByteArray(png, 0, size, options);
            if (hint == null) throw new IOException("Invalid hint pixels");
            hint.setDensity(Bitmap.DENSITY_NONE);
            final int hintHeight = hint.getHeight();
            stage = 3;
            sc = Class.forName("android.view.SurfaceControl");
            Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
            tx = Class.forName("android.view.SurfaceControl$Transaction");
            Object builder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setName", String.class).invoke(builder, "AShell ADB privacy overlay");
            excludeCapture(builder, builderClass, sc);
            builderClass.getMethod("setBufferSize", int.class, int.class).invoke(builder, 1, 1);
            builderClass.getMethod("setFormat", int.class).invoke(builder, PixelFormat.RGBA_8888);
            Method skip = null;
            if (Build.VERSION.SDK_INT >= 31) {
                // Resolve before building/showing: never substitute FLAG_SECURE (which captures black).
                skip = tx.getMethod("setSkipScreenshot", sc, boolean.class);
            }
            Class<?> displays = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = displays.getMethod("getInstance").invoke(null);
            Object info = displays.getMethod("getDisplayInfo", int.class).invoke(global, 0);
            if (info == null) throw new IllegalStateException("No default display");
            int layerStack = info.getClass().getField("layerStack").getInt(info);
            surface = builderClass.getMethod("build").invoke(builder);
            transaction = tx.getConstructor().newInstance();
            if (skip != null) skip.invoke(transaction, surface, true);
            if (Build.VERSION.SDK_INT >= 31) {
                // Own layer only: allow input below this overlay, without disabling the
                // system-wide untrusted-touch protection or giving the layer an input channel.
                tx.getMethod("setTrustedOverlay", sc, boolean.class).invoke(transaction, surface, true);
            }
            tx.getMethod("setLayerStack", sc, int.class).invoke(transaction, surface, layerStack);
            tx.getMethod("setLayer", sc, int.class).invoke(transaction, surface, Integer.MAX_VALUE - 1);
            // Use a real buffer layer, not a 16384px effect-layer crop: both the
            // compositor and screenshot exclusion now operate on submitted pixels.
            // A single black pixel scales without allocating a full-screen bitmap.
            int displayWidth = info.getClass().getField("logicalWidth").getInt(info);
            int displayHeight = info.getClass().getField("logicalHeight").getInt(info);
            Method matrix = tx.getMethod("setMatrix", sc, float.class, float.class, float.class, float.class);
            matrix.invoke(transaction, surface, (float) displayWidth, 0f, 0f, (float) displayHeight);
            tx.getMethod("setAlpha", sc, float.class).invoke(transaction, surface, 1f);
            blackSurface = Surface.class.getConstructor().newInstance();
            Surface.class.getMethod("copyFrom", sc).invoke(blackSurface, surface);
            Canvas blackCanvas = blackSurface.lockCanvas(null);
            try { blackCanvas.drawColor(Color.BLACK); }
            finally { blackSurface.unlockCanvasAndPost(blackCanvas); }

            // Show only pixels here, with the existing 60dp margin + 20dp padding.
            stage = 4;
            float density = info.getClass().getField("logicalDensityDpi").getInt(info) / 160f;
            float inset = 80f * density;
            Object hintBuilder = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setName", String.class).invoke(hintBuilder, "AShell ADB privacy hint");
            excludeCapture(hintBuilder, builderClass, sc);
            builderClass.getMethod("setBufferSize", int.class, int.class).invoke(hintBuilder, hint.getWidth(), hintHeight);
            builderClass.getMethod("setFormat", int.class).invoke(hintBuilder, PixelFormat.RGBA_8888);
            hintControl = builderClass.getMethod("build").invoke(hintBuilder);
            if (skip != null) skip.invoke(transaction, hintControl, true);
            if (Build.VERSION.SDK_INT >= 31)
                tx.getMethod("setTrustedOverlay", sc, boolean.class).invoke(transaction, hintControl, true);
            tx.getMethod("setLayerStack", sc, int.class).invoke(transaction, hintControl, layerStack);
            tx.getMethod("setLayer", sc, int.class).invoke(transaction, hintControl, Integer.MAX_VALUE);
            Method position = tx.getMethod("setPosition", sc, float.class, float.class);
            position.invoke(transaction, hintControl, inset, Math.max(0f, displayHeight - inset - hintHeight));
            // Surface's empty constructor/copyFrom are framework APIs, not public SDK APIs.
            hintSurface = Surface.class.getConstructor().newInstance();
            Surface.class.getMethod("copyFrom", sc).invoke(hintSurface, hintControl);
            Canvas canvas = hintSurface.lockCanvas(null);
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                canvas.drawBitmap(hint, 0f, 0f, null);
            } finally { hintSurface.unlockCanvasAndPost(canvas); }
            hint.recycle();
            hint = null;
            stage = 5;
            tx.getMethod("show", sc).invoke(transaction, hintControl);
            tx.getMethod("show", sc).invoke(transaction, surface);
            tx.getMethod("apply", boolean.class).invoke(transaction, true);
            System.out.println("ASHELL_OVERLAY_READY");
            System.out.flush();
            while (System.in.read() == 1) {
                heartbeat = SystemClock.elapsedRealtime();
                // Reposition after rotation using the existing lease ticks, without another timer.
                Object currentInfo = displays.getMethod("getDisplayInfo", int.class).invoke(global, 0);
                if (currentInfo == null) continue;
                int height = currentInfo.getClass().getField("logicalHeight").getInt(currentInfo);
                int width = currentInfo.getClass().getField("logicalWidth").getInt(currentInfo);
                if (height != displayHeight || width != displayWidth) {
                    displayHeight = height;
                    displayWidth = width;
                    matrix.invoke(transaction, surface, (float) width, 0f, 0f, (float) height);
                    position.invoke(transaction, hintControl, inset, Math.max(0f, height - inset - hintHeight));
                    tx.getMethod("apply", boolean.class).invoke(transaction, true);
                }
            }
        } catch (Throwable failure) {
            for (int i = 0; i < 8 && failure.getCause() != null && failure.getCause() != failure; i++)
                failure = failure.getCause();
            // Report only the actual operation/API/type, never raw framework exception text.
            String type = failure.getClass().getSimpleName();
            if (!type.matches("[A-Za-z0-9_$]{1,80}")) type = "FrameworkError";
            System.out.println("ASHELL_OVERLAY_ERROR:" + stage + ":" + Build.VERSION.SDK_INT + ":" + type);
            System.out.flush();
        } finally {
            if (hint != null) hint.recycle();
            try {
                if (surface != null && transaction != null) {
                    if (hintControl != null) tx.getMethod("remove", sc).invoke(transaction, hintControl);
                    tx.getMethod("remove", sc).invoke(transaction, surface);
                    tx.getMethod("apply", boolean.class).invoke(transaction, true);
                }
                if (hintSurface != null) hintSurface.release();
                if (blackSurface != null) blackSurface.release();
                if (hintControl != null) sc.getMethod("release").invoke(hintControl);
                if (surface != null) sc.getMethod("release").invoke(surface);
                if (transaction != null) tx.getMethod("close").invoke(transaction);
            } catch (Throwable ignored) { }
            System.out.println("ASHELL_OVERLAY_OFF");
            System.out.flush();
            System.exit(0); // also releases the SurfaceControl session if remove failed
        }
    }

    private static void excludeCapture(Object builder, Class<?> type, Class<?> sc) throws Exception {
        // Set exclusion at CREATION as well as in the first transaction. BufferQueue
        // adapters/ROM mirror implementations may snapshot the layer's creation flags.
        int flags = sc.getField("HIDDEN").getInt(null);
        if (Build.VERSION.SDK_INT >= 31) flags |= sc.getField("SKIP_SCREENSHOT").getInt(null);
        type.getMethod("setFlags", int.class).invoke(builder, flags);
        if (Build.VERSION.SDK_INT == 30) {
            type.getMethod("setMetadata", int.class, int.class).invoke(builder,
                    sc.getField("METADATA_WINDOW_TYPE").getInt(null),
                    sc.getField("WINDOW_TYPE_DONT_SCREENSHOT").getInt(null));
        }
    }
}
