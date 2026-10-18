package me.ashell.localadb;

import android.content.Context;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbStream;

/** One outstanding screenshot request, with a private lease and bounded binary frames. */
final class LocalAdbScreenshotSession implements AutoCloseable {
    private final LocalAdbClient client;
    private final AdbStream stream;
    private final DataInputStream input;
    private final AtomicBoolean cancelled;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread heartbeat;

    LocalAdbScreenshotSession(Context context, LocalAdbClient client, AtomicBoolean cancelled) throws Exception {
        this.client = client; this.cancelled = cancelled;
        String apk = context.getApplicationInfo().sourceDir.replace("'", "'\\''");
        stream = client.openMirrorStream("shell,v2,raw:CLASSPATH='" + apk
                + "' exec app_process / me.ashell.localadb.AdbScreenshotCapture");
        input = new DataInputStream(new Stdout(stream, cancelled));
        heartbeat = new Thread(() -> {
            try {
                while (!closed.get() && !cancelled.get()) {
                    control(1, 0); Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
            client.abort();
        }, "ashell-screenshot-heartbeat");
        heartbeat.setDaemon(true); heartbeat.start();
    }

    private synchronized void control(int command, int quality) throws IOException {
        if (closed.get() || cancelled.get()) throw new EOFException("Stopped");
        byte[] packet = command == 2 ? new byte[]{0, 2, 0, 0, 0, 2, (byte) quality}
                : new byte[]{0, 1, 0, 0, 0, (byte) command};
        stream.write(packet, 0, packet.length); stream.flush();
    }

    LocalAdbScreenshot.Frame read(int quality) throws IOException {
        control(2, quality);
        if (input.readInt() != AdbScreenshotCapture.MAGIC) throw new IOException("Invalid screenshot header");
        int sourceWidth = input.readInt(), sourceHeight = input.readInt();
        int width = input.readInt(), height = input.readInt(), size = input.readInt();
        AdbCaptureProfile profile = new AdbCaptureProfile(sourceWidth, sourceHeight);
        if (width != profile.width || height != profile.height || size < 4 || size > 2 * 1024 * 1024)
            throw new IOException("Invalid screenshot dimensions or size");
        byte[] jpeg = new byte[size]; input.readFully(jpeg);
        return new LocalAdbScreenshot.Frame(width, height, sourceWidth, sourceHeight, jpeg);
    }

    @Override public void close() throws IOException {
        closed.set(true); heartbeat.interrupt(); client.abort();
        // Join before reusing the client for fallback; the old heartbeat must not
        // abort the replacement connection from its finally path.
        try { heartbeat.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (heartbeat.isAlive()) throw new IOException("Screenshot writer has not stopped");
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static final class Stdout extends InputStream {
        private final AdbStream stream;
        private final AtomicBoolean cancelled;
        private final byte[] header = new byte[5], discard = new byte[4096];
        private int remaining;
        Stdout(AdbStream stream, AtomicBoolean cancelled) { this.stream = stream; this.cancelled = cancelled; }
        private void fully(byte[] bytes) throws IOException {
            for (int offset = 0; offset < bytes.length;) {
                if (cancelled.get()) throw new EOFException("Stopped");
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count <= 0) throw new EOFException();
                offset += count;
            }
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (remaining == 0) {
                fully(header);
                int channel = header[0] & 255;
                int size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (size < 0 || size > 1048576 || channel < 1 || channel > 3) throw new IOException("Invalid screenshot shell packet");
                if (channel == 3) {
                    if (size != 1) throw new IOException("Invalid screenshot exit");
                    byte[] exit = new byte[1]; fully(exit);
                    throw new IOException("Screenshot helper exited: " + (exit[0] & 255));
                }
                if (channel == 1) { remaining = size; continue; }
                while (size > 0) {
                    if (cancelled.get()) throw new EOFException("Stopped");
                    int count = stream.read(discard, 0, Math.min(size, discard.length));
                    if (count <= 0) throw new EOFException();
                    size -= count;
                }
            }
            if (cancelled.get()) throw new EOFException("Stopped");
            int count = stream.read(bytes, offset, Math.min(length, remaining));
            if (count <= 0) throw new EOFException();
            remaining -= count; return count;
        }
    }
}
