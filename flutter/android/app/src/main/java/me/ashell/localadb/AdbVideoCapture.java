package me.ashell.localadb;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Shell entry using the pinned scrcpy capturer and a live, independently owned video encoder. */
public final class AdbVideoCapture {
    static final int PROFILE_MAGIC = 0x53485046; // SHPF, device-local only
    private volatile int[] profile;
    private volatile boolean closed;
    private volatile long heartbeat = SystemClock.elapsedRealtime();

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 2000 && android.os.Process.myUid() != 0)
                throw new SecurityException("ADB shell required");
            Looper.prepareMainLooper();
            new AdbVideoCapture().run(args);
            System.exit(0);
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            System.err.println("ADB video: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        // No compile-time scrcpy dependency: these public signatures are checked
        // against the bundled 5.0.1 jar source, whose hash is verified before push.
        Class<?> optionsType = type("Options");
        Object options = optionsType.getMethod("parse", String[].class).invoke(null, (Object) args);
        type("util.Ln").getMethod("initLogLevel", type("util.Ln$Level")).invoke(null, call(options, "getLogLevel"));
        type("Workarounds").getMethod("apply").invoke(null);
        int scid = (Integer) call(options, "getScid");
        int maxSize = (Integer) call(options, "getMaxSize");
        profile = new int[]{(Integer) call(options, "getVideoBitRate"), Math.round((Float) call(options, "getMaxFps"))};
        Object control = type("video.CaptureControl").getConstructor().newInstance();
        Object capture = type("video.ScreenCapture")
                .getConstructor(type("video.VirtualDisplayListener"), optionsType).newInstance(null, options);
        HandlerThread callbacks = new HandlerThread("ashell-video-events"); callbacks.start();
        Thread lease = new Thread(() -> {
            while (SystemClock.elapsedRealtime() - heartbeat < 10000) SystemClock.sleep(500);
            // Also bounds a blocked encoder/GL/socket call after the parent closes.
            System.exit(0);
        }, "ashell-video-lease");
        lease.setDaemon(true); lease.start();
        boolean initialized = false;
        try (LocalServerSocket server = new LocalServerSocket(String.format(Locale.US, "scrcpy_%08x", scid));
             LocalSocket socket = server.accept()) {
            int peerUid = socket.getPeerCredentials().getUid();
            if (peerUid != 0 && peerUid != 2000) throw new SecurityException("ADB transport required");
            Thread updates = new Thread(() -> readProfiles(socket), "ashell-video-profile");
            updates.setDaemon(true); updates.start();
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(0x68323634); // Same H.264 packet format as scrcpy, field 41 unchanged.
            MediaCodec probe = MediaCodec.createEncoderByType("video/avc");
            MediaCodecInfo.VideoCapabilities caps;
            String encoderName;
            try {
                encoderName = probe.getName();
                caps = probe.getCodecInfo().getCapabilitiesForType("video/avc").getVideoCapabilities();
            } finally { probe.release(); }
            int alignment = Math.max(caps.getWidthAlignment(), caps.getHeightAlignment());
            Object constraints = type("video.VideoConstraints").getConstructor(int.class, int.class, MediaCodecInfo.VideoCapabilities.class)
                    .newInstance(maxSize, alignment, caps);
            capture.getClass().getMethod("init", type("video.CaptureControl"), type("video.VideoConstraints"))
                    .invoke(capture, control, constraints);
            initialized = true;
            while (!closed) {
                call(control, "consumeReset");
                call(capture, "prepare");
                Object size = call(capture, "getSize");
                int width = (Integer) call(size, "getWidth"), height = (Integer) call(size, "getHeight");
                if (width < 1 || height < 1 || width > AdbCaptureProfile.MAX_EDGE || height > AdbCaptureProfile.MAX_EDGE
                        || (long) width * height > AdbCaptureProfile.MAX_PIXELS)
                    throw new IOException("Invalid ADB video dimensions");
                output.writeInt(0x80000000); output.writeInt(width); output.writeInt(height);
                encode(capture, control, callbacks, output, encoderName, caps, width, height);
            }
        } finally {
            closed = true;
            try { if (initialized) call(capture, "release"); }
            finally { callbacks.quitSafely(); }
        }
    }

    private void readProfiles(LocalSocket socket) {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (!closed) {
                if (input.readInt() != PROFILE_MAGIC) throw new IOException("Invalid ADB profile");
                int bitrate = input.readInt(), fps = input.readInt();
                if (bitrate < 1000 || fps < 1 || fps > 120) throw new IOException("Invalid ADB profile values");
                profile = new int[]{bitrate, fps};
                heartbeat = SystemClock.elapsedRealtime();
            }
        } catch (IOException ignored) {
            closed = true;
            try { socket.close(); } catch (IOException ignoredClose) { }
        }
    }

    private void encode(Object capture, Object control, HandlerThread callbacks, DataOutputStream output,
                        String encoderName, MediaCodecInfo.VideoCapabilities caps, int width, int height) throws Exception {
        MediaCodec codec = MediaCodec.createByCodecName(encoderName);
        Surface encoderSurface = null;
        AdbVideoSurface bridge = null;
        boolean started = false, capturing = false;
        try {
            int bitrate = caps.getBitrateRange().clamp(profile[0]);
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // Input cadence is controlled live below.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = codec.createInputSurface();
            bridge = new AdbVideoSurface(encoderSurface, width, height, new Handler(callbacks.getLooper()));
            codec.start(); started = true;
            capture.getClass().getMethod("start", Surface.class).invoke(capture, bridge.input());
            capturing = true;
            Method reset = control.getClass().getMethod("isResetRequested");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[32768];
            long lastFrame = 0;
            while (!closed && !(Boolean) reset.invoke(control)) {
                int[] current = profile;
                int desired = caps.getBitrateRange().clamp(current[0]);
                if (desired != bitrate) {
                    Bundle params = new Bundle(); params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, desired);
                    codec.setParameters(params); bitrate = desired;
                }
                long now = System.nanoTime();
                if (now - lastFrame >= 1_000_000_000L / current[1]) {
                    // A one-second repeat gives new viewers a keyframe on static screens.
                    boolean refresh = now - lastFrame >= 1_000_000_000L;
                    if (refresh && bridge.hasImage()) {
                        Bundle sync = new Bundle(); sync.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(sync);
                    }
                    if (bridge.draw(now, refresh)) lastFrame = now;
                }
                int index;
                while ((index = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    try {
                        if (info.size == 0) continue;
                        if (info.size > 2 * 1024 * 1024) throw new IOException("ADB encoded frame too large");
                        ByteBuffer data = codec.getOutputBuffer(index);
                        if (data == null) throw new IOException("Missing ADB encoded frame");
                        data.position(info.offset); data.limit(info.offset + info.size);
                        long flags = info.presentationTimeUs;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags |= 1L << 62;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags |= 1L << 61;
                        output.writeLong(flags); output.writeInt(info.size);
                        while (data.hasRemaining()) {
                            int count = Math.min(chunk.length, data.remaining()); data.get(chunk, 0, count); output.write(chunk, 0, count);
                        }
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
                SystemClock.sleep(2);
            }
        } finally {
            try { if (capturing) call(capture, "stop"); }
            finally {
                try { if (bridge != null) bridge.close(); }
                finally {
                    try { if (started) codec.stop(); }
                    finally {
                        try { codec.release(); }
                        finally { if (encoderSurface != null) encoderSurface.release(); }
                    }
                }
            }
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("com.genymobile.scrcpy." + name);
    }

    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }
}
