package me.ashell.localadb;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.view.WindowManager;

/** Read-only counterpart of MainService's width / 350 integer scaling. */
final class AdbCaptureProfile {
    static final int MAX_EDGE = 4096;
    static final int MAX_PIXELS = 4 * 1024 * 1024;
    static final int FRAME_INTERVAL_MS = 80;
    // Same capture cadence as the ordinary Accessibility screenshot loop (300 ms).
    private static final int SCREENSHOT_INTERVAL_MS = 300;
    final int sourceWidth, sourceHeight, width, height;

    AdbCaptureProfile(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceWidth > 16384 || sourceHeight > 16384)
            throw new IllegalArgumentException("Invalid display size");
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        int divisor = Math.max(1, sourceWidth / 350);
        width = Math.max(1, sourceWidth / divisor);
        height = Math.max(1, sourceHeight / divisor);
        if (width > MAX_EDGE || height > MAX_EDGE || (long) width * height > MAX_PIXELS)
            throw new IllegalArgumentException("Scaled display exceeds ADB frame bounds");
    }

    @SuppressWarnings("deprecation")
    static AdbCaptureProfile current(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            return new AdbCaptureProfile(bounds.width(), bounds.height());
        }
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        return new AdbCaptureProfile(size.x, size.y);
    }

    int maxSize() { return Math.max(width, height); }

    long screenshotIntervalMs() {
        // Same source cadence as Accessibility; also respect the current video
        // delivery rate. Re-read each frame so temporary congestion can recover.
        return Math.max(SCREENSHOT_INTERVAL_MS, deliveryIntervalMs());
    }
    long layoutIntervalMs() { return Math.max(FRAME_INTERVAL_MS, deliveryIntervalMs()); }
    private long deliveryIntervalMs() {
        int fps = encoding()[1];
        return (1000L + fps - 1) / fps;
    }
    private static native int[] quality(int width, int height, int sourceWidth);

    int[] encoding() {
        // Called in the app process, including while streaming. Never load the
        // product native library in a shell helper or invent a fallback profile.
        int[] value = quality(width, height, sourceWidth);
        if (value == null || value.length != 3) throw new IllegalStateException("ADB media profile unavailable");
        return value;
    }
}
