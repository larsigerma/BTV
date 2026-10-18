package me.ashell.localadb;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;

/** Persistent shell screenshot worker: only scaled JPEG crosses ADB, never full-size raw pixels. */
public final class AdbScreenshotCapture {
    static final int MAGIC = 0x53484a50; // SHJP
    private static volatile long heartbeat = SystemClock.elapsedRealtime();

    public static void main(String[] args) {
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
        System.setOut(System.err);
        Thread lease = new Thread(() -> {
            try {
                while (SystemClock.elapsedRealtime() - heartbeat < 10000) Thread.sleep(500);
            } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "ashell-screenshot-lease");
        lease.setDaemon(true); lease.start();
        UiAutomation automation = null;
        try {
            if (android.os.Process.myUid() != 2000 && android.os.Process.myUid() != 0) return;
            Looper.prepareMainLooper();
            Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
            Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connection)
                    .newInstance(Looper.getMainLooper(), binder);
            // Screenshot-only connection: neither registers a node service nor suppresses
            // the user's Accessibility services. No ActivityThread/HWUI/font initialization.
            int noAccessibility = UiAutomation.class.getField("FLAG_DONT_USE_ACCESSIBILITY").getInt(null);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                    noAccessibility | UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            int command;
            while ((command = System.in.read()) != -1 && command != 0) {
                heartbeat = SystemClock.elapsedRealtime();
                if (command == 1) continue;
                if (command != 2) throw new IllegalArgumentException("Invalid screenshot request");
                int quality = System.in.read();
                if (quality < 1 || quality > 100) throw new IllegalArgumentException("Invalid JPEG quality");
                Bitmap source = automation.takeScreenshot();
                if (source == null) throw new IllegalStateException("System screenshot unavailable");
                Bitmap resized = source;
                try {
                    AdbCaptureProfile profile = new AdbCaptureProfile(source.getWidth(), source.getHeight());
                    if (profile.width != source.getWidth() || profile.height != source.getHeight())
                        resized = Bitmap.createScaledBitmap(source, profile.width, profile.height, true);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    if (!resized.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
                            || jpeg.size() > 2 * 1024 * 1024) throw new IllegalStateException("Screenshot encode failed");
                    output.writeInt(MAGIC);
                    output.writeInt(profile.sourceWidth); output.writeInt(profile.sourceHeight);
                    output.writeInt(profile.width); output.writeInt(profile.height);
                    output.writeInt(jpeg.size()); jpeg.writeTo(output); output.flush();
                } finally {
                    if (resized != source) resized.recycle();
                    source.recycle();
                }
            }
        } catch (Throwable error) {
            // A closed binary stream selects the existing screencap compatibility path.
            error.printStackTrace(System.err);
        } finally {
            if (automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Throwable ignored) { }
            }
            System.exit(0);
        }
    }
}
