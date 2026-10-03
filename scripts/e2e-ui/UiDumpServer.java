package dev.jacobhayes.bootleg.e2e;

import android.app.UiAutomation;
import android.graphics.Point;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeoutException;

/**
 * Serves `uiautomator dump` XML and `content` command output over a local socket from one
 * long-lived process, for the on-device e2e scripts.
 *
 * <p>`uiautomator dump` costs ~2.4 s on the Pixel 6 (a fresh app_process, a fresh UiAutomation
 * connection and a fixed 1 s idle wait) and `content call` ~1 s (a fresh app_process). The e2e
 * scripts run hundreds of each, so this keeps one process and one connection. It reuses
 * uiautomator.jar's own dumper, so the XML is the same as `uiautomator dump` (active window,
 * uncompressed), and content.jar's own command line, so the output is the same as `content`.
 *
 * <p>Run (as shell): CLASSPATH=/system/framework/uiautomator.jar:/system/framework/content.jar:THIS.jar
 * app_process /system/bin dev.jacobhayes.bootleg.e2e.UiDumpServer SOCKET IDLE_MS IDLE_TIMEOUT_MS
 * EXIT_AFTER_UNUSED_MS
 *
 * <p>Protocol: the client connects to localabstract:SOCKET and sends one line: "dump", "quit", or
 * "content" followed by tab-separated `content` arguments. The answer is the XML or the command's
 * output, then the server closes the connection; an empty dump answer means the dump failed. Only
 * one UiAutomation connection can exist, so plain `uiautomator dump` fails while this runs: it
 * exits on its own after EXIT_AFTER_UNUSED_MS without requests. Only shell and root may connect
 * (the peer's uid is checked), requests and answers are size-bounded, and a client that stalls hits
 * the socket's read/write timeout.
 */
public final class UiDumpServer {
    private static final int ROOT_TRIES_MAX = 5;
    private static final long ROOT_RETRY_MS = 100;
    private static final long WATCHDOG_INTERVAL_MS = 1000;
    private static final int REQUEST_BYTES_MAX = 4096;
    private static final int CONTENT_OUTPUT_BYTES_MAX = 1 << 20;
    private static final long DUMP_BYTES_MAX = 8L << 20;
    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int UID_ROOT = 0;
    private static final int UID_SHELL = 2000;

    private static volatile long lastRequestMs = System.currentTimeMillis();

    private final UiAutomation automation;
    private final long idleMs;
    private final long idleTimeoutMs;
    private final Method dumpToFile;
    private final Object displayManager;
    private final Method realDisplay;
    private final Method contentMain;
    private final File dumpFile;

    private UiDumpServer(String socketName, long idleMs, long idleTimeoutMs) throws Exception {
        this.idleMs = idleMs;
        this.idleTimeoutMs = idleTimeoutMs;
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getConstructor().newInstance();
        wrapperClass.getMethod("connect").invoke(wrapper);
        wrapperClass.getMethod("setCompressedLayoutHierarchy", boolean.class).invoke(wrapper, false);
        automation = (UiAutomation) wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
        dumpToFile = Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper")
            .getMethod("dumpWindowToFile", AccessibilityNodeInfo.class, File.class, int.class, int.class, int.class);
        Class<?> displayManagerClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        displayManager = displayManagerClass.getMethod("getInstance").invoke(null);
        realDisplay = displayManagerClass.getMethod("getRealDisplay", int.class);
        contentMain = Class.forName("com.android.commands.content.Content").getMethod("main", String[].class);
        dumpFile = new File("/data/local/tmp/" + socketName + ".xml");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: UiDumpServer SOCKET IDLE_MS IDLE_TIMEOUT_MS EXIT_AFTER_UNUSED_MS");
            System.exit(2);
        }
        String socketName = args[0];
        long idleMs = Long.parseLong(args[1]);
        long idleTimeoutMs = Long.parseLong(args[2]);
        long exitAfterUnusedMs = Long.parseLong(args[3]);
        if (idleMs < 0 || idleTimeoutMs < idleMs || exitAfterUnusedMs <= 0) {
            throw new IllegalArgumentException("need 0 <= IDLE_MS <= IDLE_TIMEOUT_MS and EXIT_AFTER_UNUSED_MS > 0");
        }
        UiDumpServer server = new UiDumpServer(socketName, idleMs, idleTimeoutMs);
        startWatchdog(exitAfterUnusedMs);
        LocalServerSocket socket = new LocalServerSocket(socketName);
        System.err.println("UiDumpServer: listening on localabstract:" + socketName);
        while (true) {
            try (LocalSocket client = socket.accept()) {
                lastRequestMs = System.currentTimeMillis();
                int peerUid = client.getPeerCredentials().getUid();
                if (peerUid != UID_SHELL && peerUid != UID_ROOT) {
                    System.err.println("UiDumpServer: refused uid " + peerUid);
                    continue;
                }
                // Applies to reads and writes alike (SO_RCVTIMEO and SO_SNDTIMEO).
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                String request = readRequest(client.getInputStream());
                OutputStream out = client.getOutputStream();
                if (request == null) continue;
                String[] words = request.split("\t", -1);
                switch (words[0]) {
                    case "quit":
                        out.write("bye\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.exit(0);
                        break;
                    case "dump":
                        out.write(server.dump());
                        break;
                    case "content":
                        String[] contentArgs = new String[words.length - 1];
                        System.arraycopy(words, 1, contentArgs, 0, contentArgs.length);
                        out.write(server.content(contentArgs));
                        break;
                    default:
                        System.err.println("UiDumpServer: unknown request " + words[0]);
                }
                out.flush();
            } catch (Exception failure) {
                System.err.println("UiDumpServer: request failed: " + failure);
            }
        }
    }

    /** One request line, without its newline; null when it is missing or longer than REQUEST_BYTES_MAX. */
    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int length = 0; length <= REQUEST_BYTES_MAX; length++) {
            int next = in.read();
            if (next == -1) return null;
            if (next == '\n') return line.toString(StandardCharsets.UTF_8.name());
            line.write(next);
        }
        System.err.println("UiDumpServer: request longer than " + REQUEST_BYTES_MAX + " bytes");
        return null;
    }

    private static void startWatchdog(long exitAfterUnusedMs) {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (System.currentTimeMillis() - lastRequestMs > exitAfterUnusedMs) {
                    System.err.println("UiDumpServer: unused for " + exitAfterUnusedMs + " ms, exiting");
                    System.exit(0);
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private byte[] dump() throws Exception {
        try {
            automation.waitForIdle(idleMs, idleTimeoutMs);
        } catch (TimeoutException busy) {
            // A spinner never idles; dump anyway (callers poll for the state they need).
        }
        // The connection caches nodes and relies on accessibility events to refresh them, which
        // Compose doesn't always send to a UiAutomation client: start every dump from a fresh tree,
        // as a fresh `uiautomator dump` does.
        automation.clearCache();
        AccessibilityNodeInfo root = null;
        for (int attempt = 1; attempt <= ROOT_TRIES_MAX && root == null; attempt++) {
            root = automation.getRootInActiveWindow();
            if (root == null) Thread.sleep(ROOT_RETRY_MS);
        }
        if (root == null) {
            System.err.println("UiDumpServer: no active window root");
            return new byte[0];
        }
        Display display = (Display) realDisplay.invoke(displayManager, Display.DEFAULT_DISPLAY);
        Point size = new Point();
        display.getRealSize(size);
        // The dumper swallows write errors: without a fresh file it would serve the previous screen.
        if (dumpFile.exists() && !dumpFile.delete()) {
            System.err.println("UiDumpServer: could not delete the previous dump " + dumpFile);
            return new byte[0];
        }
        dumpToFile.invoke(null, root, dumpFile, display.getRotation(), size.x, size.y);
        long dumpBytes = dumpFile.length();
        if (!dumpFile.isFile() || dumpBytes == 0 || dumpBytes > DUMP_BYTES_MAX) {
            System.err.println("UiDumpServer: no usable dump written (" + dumpBytes + " bytes)");
            return new byte[0];
        }
        return Files.readAllBytes(dumpFile.toPath());
    }

    /** Runs content.jar's command line in this process, capturing what it prints. */
    private byte[] content(String[] args) {
        BoundedOutput buffer = new BoundedOutput(CONTENT_OUTPUT_BYTES_MAX);
        PrintStream capture = new PrintStream(buffer, true);
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(capture);
        System.setErr(capture);
        try {
            contentMain.invoke(null, (Object) args);
        } catch (Exception failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            capture.println("Error: " + cause);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        capture.flush();
        return buffer.toByteArray();
    }

    /** Keeps the first limitBytes bytes written and marks the output as truncated past that. */
    private static final class BoundedOutput extends OutputStream {
        private static final byte[] TRUNCATED = "\n[UiDumpServer: output truncated]\n".getBytes(StandardCharsets.UTF_8);
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final int limitBytes;
        private boolean truncated;

        BoundedOutput(int limitBytes) {
            this.limitBytes = limitBytes;
        }

        @Override
        public void write(int value) {
            if (kept.size() < limitBytes) kept.write(value); else truncated = true;
        }

        byte[] toByteArray() {
            if (!truncated) return kept.toByteArray();
            ByteArrayOutputStream marked = new ByteArrayOutputStream();
            marked.write(kept.toByteArray(), 0, kept.size());
            marked.write(TRUNCATED, 0, TRUNCATED.length);
            return marked.toByteArray();
        }
    }
}
