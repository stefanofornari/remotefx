package spike.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Spike: a minimal RFB (VNC) server living inside a JavaFX application.
 *
 * <ul>
 *   <li>Frames: {@link Robot#getScreenCapture} of the whole (virtual) screen, so popups and dialogs are included.</li>
 *   <li>Updates: 64x64 tile diff per client, Raw encoding only.</li>
 *   <li>Pointer: {@link Robot} mouse move/press/wheel.</li>
 *   <li>Keys: synthetic {@link KeyEvent}s fired at the focus owner (robot keys only know KeyCode, not characters).</li>
 *   <li>Security: none. Binds to loopback only; reach it through {@code ssh -L}.</li>
 * </ul>
 *
 * Call {@link #start(int)} from the FX application thread.
 */
public final class VncServer {

    private static final int TILE = 64;
    private static final long MIN_FRAME_NANOS = 40_000_000L; // cap capture at ~25 fps
    private static final long IDLE_FRAME_NANOS = 200_000_000L; // capture rate once the screen stopped changing
    private static final int IDLE_AFTER = 10; // identical captures in a row before slowing down
    private static final int ENCODING_ZRLE = 16;
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final boolean DEBUG = Settings.bool("debug");

    private static final MouseButton[] BUTTONS = {MouseButton.PRIMARY, MouseButton.MIDDLE, MouseButton.SECONDARY};
    private static final Map<Integer, KeyCode> SPECIAL = new HashMap<>();

    static {
        SPECIAL.put(0xff08, KeyCode.BACK_SPACE);
        SPECIAL.put(0xff09, KeyCode.TAB);
        SPECIAL.put(0xff0d, KeyCode.ENTER);
        SPECIAL.put(0xff8d, KeyCode.ENTER);   // keypad enter
        SPECIAL.put(0xff1b, KeyCode.ESCAPE);
        SPECIAL.put(0xffff, KeyCode.DELETE);
        SPECIAL.put(0xff50, KeyCode.HOME);
        SPECIAL.put(0xff51, KeyCode.LEFT);
        SPECIAL.put(0xff52, KeyCode.UP);
        SPECIAL.put(0xff53, KeyCode.RIGHT);
        SPECIAL.put(0xff54, KeyCode.DOWN);
        SPECIAL.put(0xff55, KeyCode.PAGE_UP);
        SPECIAL.put(0xff56, KeyCode.PAGE_DOWN);
        SPECIAL.put(0xff57, KeyCode.END);
        SPECIAL.put(0xff63, KeyCode.INSERT);
        SPECIAL.put(0xff67, KeyCode.CONTEXT_MENU);
        SPECIAL.put(0xffe1, KeyCode.SHIFT);
        SPECIAL.put(0xffe2, KeyCode.SHIFT);
        SPECIAL.put(0xffe3, KeyCode.CONTROL);
        SPECIAL.put(0xffe4, KeyCode.CONTROL);
        SPECIAL.put(0xffe9, KeyCode.ALT);
        SPECIAL.put(0xffea, KeyCode.ALT);
        SPECIAL.put(0xffe7, KeyCode.META);
        SPECIAL.put(0xffe8, KeyCode.META);
        for (int i = 1; i <= 12; i++) {
            SPECIAL.put(0xffbd + i, KeyCode.valueOf("F" + i));
        }
    }

    private final int port;
    private final String password; // empty = no authentication
    private final String desktopName = "JavaFX (Monocle)";
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Object frameLock = new Object();

    // FX thread only
    private Robot robot;
    private AnimationTimer timer;
    private long lastCaptureNanos;
    private boolean useScenes = "scene".equalsIgnoreCase(Settings.get("capture", "robot"));
    private int blankFrames;
    private boolean robotVerified; // robot capture produced a non-black frame at least once
    private boolean sizeMismatchLogged;
    private int unchangedFrames;
    private WritableImage captureImage; // reused by the robot
    private int[] windowBuf; // reused by captureScenes
    private int lastMask;
    private Stage activeWindow; // window of the last button press
    private WindowManager wm;     // null when the window manager is off
    private boolean shift, ctrl, alt, meta;

    private ServerSocket serverSocket;
    private int width, height;

    /** A reference-counted pixel buffer: a published frame is never modified, and is recycled once unreferenced. */
    private static final class Frame {
        final int[] px;
        int refs; // guarded by frameLock

        Frame(int n) {
            px = new int[n];
        }
    }

    // guarded by frameLock (current is only ever replaced by the capture thread)
    private final List<Frame> pool = new ArrayList<>();
    private Frame current;
    private long frameSeq;

    private final ArrayDeque<int[]> pointerQueue = new ArrayDeque<>(); // {mask, x, y}, guarded by itself

    private VncServer(int port) {
        this.port = port;
        String pw = System.getenv("VNC_PASSWORD"); // env, so it does not show up in `ps`
        this.password = pw != null ? pw : System.getProperty("vnc.password", "");
    }

    private static VncServer instance; // FX thread only

    /** Starts the server (a second call returns the running one). Must be called on the FX application thread. */
    public static VncServer start(int port) throws IOException {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("VncServer.start must be called on the FX application thread");
        }
        if (instance != null) return instance;
        VncServer s = new VncServer(port);
        s.doStart();
        instance = s;
        return s;
    }

    public void stop() {
        Platform.runLater(() -> {
            if (timer != null) timer.stop();
        });
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.closeQuietly();
    }

    private void doStart() throws IOException {
        robot = new Robot();
        boolean monocle = "Monocle".equalsIgnoreCase(System.getProperty("glass.platform"));
        if (Boolean.parseBoolean(Settings.get("wm", String.valueOf(monocle)))) {
            wm = new WindowManager(s -> activeWindow = s);
            info("window manager: one top-level window visible at a time, filling the screen");
        } else {
            Window.getWindows().addListener((ListChangeListener<Window>) c -> {
                while (c.next()) {
                    if (c.wasAdded() && c.getAddedSubList().stream().anyMatch(w -> w instanceof Stage)) {
                        activeWindow = null; // a new window (a dialog, say) gets the keys until the next click
                    }
                }
            });
        }
        Rectangle2D b = Screen.getPrimary().getBounds();
        width = (int) b.getWidth();
        height = (int) b.getHeight();

        InetAddress bind = InetAddress.getByName(Settings.get("bind", "127.0.0.1"));
        serverSocket = new ServerSocket(port, 4, bind);
        if (!password.isEmpty()) {
            info("authentication: password (VNC authentication only uses the first 8 characters)");
        } else {
            info("authentication: NONE (set VNC_PASSWORD; macOS Screen Sharing refuses servers without one)");
            if (!bind.isLoopbackAddress()) {
                info("WARNING: anyone who can reach " + bind.getHostAddress() + ":" + port + " can control this UI");
            }
        }
        info("capture mode: " + (useScenes ? "scene" : "robot") + ", windows showing: " + Window.getWindows().size());
        Thread acceptor = new Thread(this::acceptLoop, "vnc-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                capture(now);
            }
        };
        timer.start();
        info("listening on " + bind.getHostAddress() + ":" + port + ", screen " + width + "x" + height);
    }

    private void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(new Client(s), "vnc-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) info("accept failed: " + e);
        }
    }

    // ------------------------------------------------------------------ capture (FX thread)

    private void capture(long now) {
        if (!anyClientWaiting()) return;
        long interval = unchangedFrames >= IDLE_AFTER ? IDLE_FRAME_NANOS : MIN_FRAME_NANOS;
        if (now - lastCaptureNanos < interval) return;
        lastCaptureNanos = now;

        Frame f = acquireFrame();
        if (!(useScenes ? captureScenes(f.px) : captureRobot(f.px))) {
            release(f);
            return;
        }
        if (!useScenes && !robotVerified) {
            if (!isBlank(f.px)) {
                robotVerified = true;
            } else if (hasShowingWindow()) {
                if (++blankFrames >= 5) {
                    info("robot capture is all black; switching to per-window scene snapshots (same as -Dvnc.capture=scene)");
                    useScenes = true;
                }
                release(f);
                return;
            }
        }
        if (current != null && Arrays.equals(current.px, f.px)) {
            unchangedFrames++;
            release(f);
            return;
        }
        unchangedFrames = 0;
        if (DEBUG && frameSeq == 0) info("first frame captured (" + (useScenes ? "scene" : "robot") + ")");
        synchronized (frameLock) {
            Frame old = current;
            current = f; // keeps the reference taken by acquireFrame
            frameSeq++;
            if (old != null) old.refs--;
            frameLock.notifyAll();
        }
    }

    /** Capture is pointless unless some client has an update request outstanding. */
    private boolean anyClientWaiting() {
        for (Client c : clients) {
            if (c.pending) return true;
        }
        return false;
    }

    private Frame acquireFrame() {
        synchronized (frameLock) {
            for (Frame f : pool) {
                if (f.refs == 0) {
                    f.refs = 1;
                    return f;
                }
            }
            Frame f = new Frame(width * height);
            f.refs = 1;
            pool.add(f);
            return f;
        }
    }

    private void release(Frame f) {
        if (f == null) return;
        synchronized (frameLock) {
            f.refs--;
        }
    }

    /** Whole-screen grab through the platform robot into dst; false if unusable. */
    private boolean captureRobot(int[] dst) {
        try {
            WritableImage img = robot.getScreenCapture(captureImage, 0, 0, width, height);
            if ((int) img.getWidth() != width || (int) img.getHeight() != height) {
                if (!sizeMismatchLogged) {
                    sizeMismatchLogged = true;
                    info("capture is " + img.getWidth() + "x" + img.getHeight() + ", expected " + width + "x" + height + " (HiDPI scaling?)");
                }
                return false;
            }
            captureImage = img;
            img.getPixelReader().getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), dst, 0, width);
            return true;
        } catch (RuntimeException e) {
            info("robot capture failed (" + e + "); switching to per-window scene snapshots");
            useScenes = true;
            return false;
        }
    }

    /** Fallback: snapshot every showing window (popups included) and paint it at its screen position. */
    private boolean captureScenes(int[] px) {
        Arrays.fill(px, 0);
        for (Window w : Window.getWindows()) {
            Scene s = w.getScene();
            if (s == null || !w.isShowing()) continue;
            int ox = (int) Math.round(w.getX() + s.getX());
            int oy = (int) Math.round(w.getY() + s.getY());
            if (ox >= width || oy >= height || ox + w.getWidth() <= 0 || oy + w.getHeight() <= 0) continue; // off-screen
            WritableImage snap = s.snapshot(null);
            int sw = (int) snap.getWidth(), sh = (int) snap.getHeight();
            if (windowBuf == null || windowBuf.length < sw * sh) windowBuf = new int[sw * sh];
            int[] sp = windowBuf;
            snap.getPixelReader().getPixels(0, 0, sw, sh, WritablePixelFormat.getIntArgbInstance(), sp, 0, sw);
            int x0 = Math.max(0, -ox), x1 = Math.min(sw, width - ox);
            for (int y = Math.max(0, -oy); y < sh && oy + y < height; y++) {
                int src = y * sw, dst = (oy + y) * width + ox;
                for (int x = x0; x < x1; x++) {
                    int p = sp[src + x];
                    if ((p >>> 24) != 0) px[dst + x] = p;
                }
            }
        }
        return true;
    }

    private static boolean isBlank(int[] px) {
        for (int p : px) {
            if ((p & 0xffffff) != 0) return false;
        }
        return true;
    }

    private static boolean hasShowingWindow() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ input (marshalled to the FX thread)

    /** Consecutive events with the same button mask are just motion: only the last one matters. */
    private void pointer(int mask, int x, int y) {
        boolean schedule;
        synchronized (pointerQueue) {
            int[] last = pointerQueue.peekLast();
            schedule = last == null;
            if (last != null && last[0] == mask) {
                last[1] = x;
                last[2] = y;
            } else {
                pointerQueue.add(new int[]{mask, x, y});
            }
        }
        if (schedule) Platform.runLater(this::drainPointer);
    }

    private void drainPointer() {
        List<int[]> batch;
        synchronized (pointerQueue) {
            batch = new ArrayList<>(pointerQueue);
            pointerQueue.clear();
        }
        for (int[] e : batch) onPointer(e[0], e[1], e[2]);
    }

    private void onPointer(int mask, int x, int y) {
        unchangedFrames = 0;
        if (DEBUG) info("pointer " + x + "," + y + " mask=" + mask);
        robot.mouseMove(x, y);
        // With no window manager nothing ever takes focus: make the clicked window the active one
        // (before the press, so the click lands in an already focused window) and send keys to it.
        if ((mask & 7 & ~lastMask) != 0) activate(windowAt(x, y));
        for (int bit = 0; bit < 3; bit++) {
            boolean now = ((mask >> bit) & 1) != 0;
            boolean was = ((lastMask >> bit) & 1) != 0;
            if (now && !was) robot.mousePress(BUTTONS[bit]);
            if (!now && was) robot.mouseRelease(BUTTONS[bit]);
        }
        // RFB buttons 4/5 are the wheel: bit 3 = up, bit 4 = down (one notch per press)
        if ((mask & 8) != 0 && (lastMask & 8) == 0) robot.mouseWheel(-1);
        if ((mask & 16) != 0 && (lastMask & 16) == 0) robot.mouseWheel(1);
        lastMask = mask;
    }

    /** Topmost (most recently shown) application window containing the screen point, or null. */
    private Stage windowAt(int x, int y) {
        List<Window> all = Window.getWindows();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof Stage s && s.isShowing() && s.getScene() != null
                    && !(wm != null && wm.isInternal(s))
                    && x >= s.getX() && x < s.getX() + s.getWidth()
                    && y >= s.getY() && y < s.getY() + s.getHeight()) {
                return s;
            }
        }
        return null;
    }

    private void activate(Stage s) {
        if (DEBUG) dumpWindows();
        if (s == null) {
            if (DEBUG) info("click outside every window");
            return;
        }
        activeWindow = s;
        if (!s.isFocused()) s.requestFocus();
        if (DEBUG) {
            Node owner = s.getScene().getFocusOwner();
            info("active window: '" + s.getTitle() + "' focused=" + s.isFocused()
                    + " focusOwner=" + (owner == null ? "none" : owner.getClass().getSimpleName() + " focused=" + owner.isFocused()));
        }
    }

    private static void dumpWindows() {
        for (Window w : Window.getWindows()) {
            info("  window " + w.getClass().getSimpleName()
                    + (w instanceof Stage st ? " '" + st.getTitle() + "'" : "")
                    + " showing=" + w.isShowing() + " focused=" + w.isFocused()
                    + " at " + (int) w.getX() + "," + (int) w.getY() + " size " + (int) w.getWidth() + "x" + (int) w.getHeight());
        }
    }

    private void key(boolean down, int keysym) {
        Platform.runLater(() -> onKey(down, keysym));
    }

    private void onKey(boolean down, int keysym) {
        unchangedFrames = 0;
        if (DEBUG) info("key " + (down ? "down" : "up") + " 0x" + Integer.toHexString(keysym));
        Node target = focusTarget();
        if (target == null) return;
        if (DEBUG && down) info("key target: " + target.getClass().getSimpleName() + " in "
                + (target.getScene().getWindow() instanceof Stage st ? "'" + st.getTitle() + "'" : "a popup"));

        KeyCode special = SPECIAL.get(keysym);
        if (special != null) {
            switch (special) {
                case SHIFT -> shift = down;
                case CONTROL -> ctrl = down;
                case ALT -> alt = down;
                case META -> meta = down;
                default -> { }
            }
            fire(target, down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, special);
            return;
        }

        int cp = toCodePoint(keysym);
        if (cp < 0) return;
        KeyCode code = cp == ' ' ? KeyCode.SPACE : codeFor(cp);
        if (!down) {
            fire(target, KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, code);
            return;
        }
        fire(target, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, code);
        // Ctrl/Meta combos are shortcuts, not text. Alt is deliberately ignored here:
        // AltGr (needed for @ # [ ] on many layouts) often arrives as Ctrl+Alt or Alt.
        if (!ctrl && !meta) {
            Node t2 = focusTarget(); // the pressed handler may have moved focus
            if (t2 != null) {
                fire(t2, KeyEvent.KEY_TYPED, new String(Character.toChars(cp)), KeyCode.UNDEFINED);
            }
        }
    }

    private void fire(Node target, EventType<KeyEvent> type, String character, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(type, character, "", code, shift, ctrl, alt, meta));
    }

    /**
     * The window last clicked, else the most recently shown focused one, else the most recently shown.
     * Monocle can report several windows as focused at once, so "focused" alone cannot decide.
     */
    private Node focusTarget() {
        Window focused = null, lastShown = null;
        for (Window w : Window.getWindows()) {
            if (w.getScene() == null || !w.isShowing() || (wm != null && wm.isInternal(w))) continue;
            if (w.isFocused()) focused = w; // keep going: the last focused one wins
            lastShown = w;
        }
        boolean activeUsable = activeWindow != null && activeWindow.isShowing() && activeWindow.getScene() != null;
        Window chosen = activeUsable ? activeWindow : focused != null ? focused : lastShown;
        if (chosen == null) return null;
        Scene s = chosen.getScene();
        Node owner = s.getFocusOwner();
        return owner != null ? owner : s.getRoot();
    }

    private static KeyCode codeFor(int cp) {
        if (cp > 0xFFFF) return KeyCode.UNDEFINED;
        try {
            KeyCode c = KeyCode.getKeyCode(String.valueOf(Character.toUpperCase((char) cp)));
            return c != null ? c : KeyCode.UNDEFINED;
        } catch (RuntimeException e) {
            return KeyCode.UNDEFINED;
        }
    }

    private static int toCodePoint(int ks) {
        if (ks >= 0x20 && ks <= 0x7e) return ks;
        if (ks >= 0xa0 && ks <= 0xff) return ks;               // Latin-1 keysyms equal their code points
        if (ks >= 0xffb0 && ks <= 0xffb9) return '0' + (ks - 0xffb0); // keypad digits
        if ((ks >>> 24) == 0x01) return ks & 0x00ffffff;       // Unicode keysyms
        return -1;
    }

    // ------------------------------------------------------------------ VNC authentication helpers

    /** VNC quirk: the DES key is the first 8 password bytes, each with its bits reversed. */
    private static byte[] desKey(String password) {
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] key = new byte[8];
        for (int i = 0; i < 8; i++) {
            int b = i < pw.length ? pw[i] & 0xff : 0;
            key[i] = (byte) (Integer.reverse(b) >>> 24);
        }
        return key;
    }

    private static boolean checkResponse(String password, byte[] challenge, byte[] response)
            throws GeneralSecurityException {
        Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
        des.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(desKey(password), "DES"));
        return MessageDigest.isEqual(des.doFinal(challenge), response);
    }

    // ------------------------------------------------------------------ pixel format

    private record PixelFmt(int bpp, boolean big, int rMax, int gMax, int bMax,
                            int rShift, int gShift, int bShift, boolean trueColour) {

        static final PixelFmt DEFAULT = new PixelFmt(32, false, 255, 255, 255, 16, 8, 0, true);

        static PixelFmt read(DataInputStream in) throws IOException {
            int bpp = in.readUnsignedByte();
            in.readUnsignedByte(); // depth
            boolean big = in.readUnsignedByte() != 0;
            boolean tc = in.readUnsignedByte() != 0;
            int rm = in.readUnsignedShort(), gm = in.readUnsignedShort(), bm = in.readUnsignedShort();
            int rs = in.readUnsignedByte(), gs = in.readUnsignedByte(), bs = in.readUnsignedByte();
            in.skipNBytes(3);
            return new PixelFmt(bpp, big, rm, gm, bm, rs, gs, bs, tc);
        }

        void write(DataOutputStream out) throws IOException {
            out.writeByte(bpp);
            out.writeByte(24); // depth
            out.writeByte(big ? 1 : 0);
            out.writeByte(trueColour ? 1 : 0);
            out.writeShort(rMax);
            out.writeShort(gMax);
            out.writeShort(bMax);
            out.writeByte(rShift);
            out.writeByte(gShift);
            out.writeByte(bShift);
            out.write(new byte[3]);
        }

        boolean supported() {
            return trueColour && (bpp == 8 || bpp == 16 || bpp == 32);
        }

        /** ZRLE sends 3-byte CPIXELs when a 32 bpp format only uses 24 bits (spec: depth <= 24, bits in the low bytes). */
        boolean compactCpixel() {
            return bpp == 32 && rMax == 255 && gMax == 255 && bMax == 255 && rShift <= 16 && gShift <= 16 && bShift <= 16;
        }
    }

    // ------------------------------------------------------------------ per-client protocol

    private final class Client implements Runnable {
        private final Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile PixelFmt pf = PixelFmt.DEFAULT;
        private volatile boolean zrle;
        private volatile boolean pending; // written under frameLock

        // guarded by frameLock
        private boolean forceFull, closed;

        // sender thread only
        private Frame prev;
        private long lastSeq;
        private byte[] row = new byte[0];
        private PixelFmt lutFmt;
        private int[] rt, gt, bt; // per-channel lookup tables for the current pixel format
        private Deflater deflater;
        private byte[] zin = new byte[0], zout = new byte[0];
        private int zinLen, zoutLen;
        private final int[] tile = new int[TILE * TILE];
        private final byte[] tileIdx = new byte[TILE * TILE];
        private final int[] palette = new int[16];
        private final int[] one = new int[1];

        Client(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (socket) {
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
                handshake();
                clients.add(this);
                info("client connected: " + socket.getRemoteSocketAddress());
                Thread sender = new Thread(this::sendLoop, "vnc-send");
                sender.setDaemon(true);
                sender.start();
                readLoop();
            } catch (EOFException | SocketException e) {
                // client went away
            } catch (IOException e) {
                info("client error: " + e);
            } finally {
                clients.remove(this);
                synchronized (frameLock) {
                    closed = true;
                    frameLock.notifyAll();
                }
                info("client disconnected");
            }
        }

        void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void handshake() throws IOException {
            out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] ver = in.readNBytes(12);
            int minor;
            try {
                minor = Integer.parseInt(new String(ver, 8, 3, StandardCharsets.US_ASCII));
            } catch (RuntimeException e) {
                throw new IOException("bad protocol version string");
            }
            int secType = password.isEmpty() ? 1 : 2; // 1 = None, 2 = VNC authentication
            if (minor >= 7) {
                out.writeByte(1); // one security type
                out.writeByte(secType);
                out.flush();
                if (in.readUnsignedByte() != secType) throw new IOException("client rejected security type " + secType);
            } else {
                out.writeInt(secType); // 3.3: the server dictates the security type
                out.flush();
            }
            if (secType == 2) {
                authenticate(minor);
            } else if (minor >= 8) {
                out.writeInt(0); // SecurityResult OK (None only has one from 3.8 on)
                out.flush();
            }
            in.readUnsignedByte(); // ClientInit: shared flag, ignored

            out.writeShort(width);
            out.writeShort(height);
            PixelFmt.DEFAULT.write(out);
            byte[] name = desktopName.getBytes(StandardCharsets.UTF_8);
            out.writeInt(name.length);
            out.write(name);
            out.flush();
        }

        /** Classic VNC authentication: 16 random bytes, answered with their DES encryption under the password. */
        private void authenticate(int minor) throws IOException {
            byte[] challenge = new byte[16];
            new SecureRandom().nextBytes(challenge);
            out.write(challenge);
            out.flush();
            byte[] response = in.readNBytes(16);
            boolean ok;
            try {
                ok = response.length == 16 && checkResponse(password, challenge, response);
            } catch (GeneralSecurityException e) {
                throw new IOException("DES not available: " + e, e);
            }
            if (ok) {
                out.writeInt(0); // SecurityResult OK
                out.flush();
                return;
            }
            out.writeInt(1); // failed
            if (minor >= 8) {
                byte[] reason = "Authentication failed".getBytes(StandardCharsets.US_ASCII);
                out.writeInt(reason.length);
                out.write(reason);
            }
            out.flush();
            throw new IOException("authentication failed from " + socket.getRemoteSocketAddress());
        }

        private void readLoop() throws IOException {
            while (true) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0 -> { // SetPixelFormat
                        in.skipNBytes(3);
                        PixelFmt p = PixelFmt.read(in);
                        if (DEBUG) info("SetPixelFormat " + p);
                        if (p.supported()) pf = p;
                        else info("unsupported pixel format requested: " + p);
                    }
                    case 2 -> { // SetEncodings: we speak ZRLE and Raw
                        in.skipNBytes(1);
                        int n = in.readUnsignedShort();
                        boolean z = false;
                        for (int i = 0; i < n; i++) {
                            if (in.readInt() == ENCODING_ZRLE) z = true;
                        }
                        zrle = z;
                    }
                    case 3 -> { // FramebufferUpdateRequest
                        boolean incremental = in.readUnsignedByte() != 0;
                        in.skipNBytes(8); // x, y, w, h: we always consider the whole screen
                        synchronized (frameLock) {
                            pending = true;
                            if (!incremental) forceFull = true;
                            frameLock.notifyAll();
                        }
                    }
                    case 4 -> { // KeyEvent
                        boolean down = in.readUnsignedByte() != 0;
                        in.skipNBytes(2);
                        key(down, in.readInt());
                    }
                    case 5 -> { // PointerEvent
                        int mask = in.readUnsignedByte();
                        int x = in.readUnsignedShort();
                        int y = in.readUnsignedShort();
                        pointer(mask, x, y);
                    }
                    case 6 -> { // ClientCutText: ignored
                        in.skipNBytes(3);
                        in.skipNBytes(in.readInt() & 0xffffffffL);
                    }
                    default -> throw new IOException("unsupported client message type " + type);
                }
            }
        }

        private void sendLoop() {
            try {
                while (true) {
                    Frame cur;
                    long seq;
                    boolean full;
                    synchronized (frameLock) {
                        while (!closed && !(pending && current != null && (forceFull || frameSeq != lastSeq))) {
                            frameLock.wait(1000);
                        }
                        if (closed) return;
                        cur = current;
                        cur.refs++;
                        seq = frameSeq;
                        full = forceFull;
                        forceFull = false;
                    }
                    List<int[]> rects = (full || prev == null)
                            ? List.of(new int[]{0, 0, width, height})
                            : diff(prev.px, cur.px);
                    lastSeq = seq;
                    if (rects.isEmpty()) { // nothing changed: keep the request pending
                        release(cur);
                        continue;
                    }
                    synchronized (frameLock) {
                        pending = false;
                    }
                    Frame old = prev;
                    prev = cur;
                    release(old);
                    writeUpdate(cur.px, rects, pf);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (DEBUG) info("send failed: " + e);
            } finally {
                release(prev);
                prev = null;
                if (deflater != null) deflater.end();
                closeQuietly();
            }
        }

        private void writeUpdate(int[] px, List<int[]> rects, PixelFmt f) throws IOException {
            prepare(f);
            boolean z = zrle;
            if (DEBUG) info("sending update: " + rects.size() + " rect(s), format " + f + (z ? ", ZRLE" : ", Raw"));
            out.writeByte(0); // FramebufferUpdate
            out.writeByte(0);
            out.writeShort(rects.size());
            for (int[] r : rects) {
                out.writeShort(r[0]);
                out.writeShort(r[1]);
                out.writeShort(r[2]);
                out.writeShort(r[3]);
                out.writeInt(z ? ENCODING_ZRLE : 0);
                if (z) writeZrle(px, r, f);
                else writeRaw(px, r, f);
            }
            out.flush();
        }

        private void prepare(PixelFmt f) {
            if (f.equals(lutFmt)) return;
            lutFmt = f;
            if (f.equals(PixelFmt.DEFAULT)) return;
            rt = channelTable(f.rMax(), f.rShift());
            gt = channelTable(f.gMax(), f.gShift());
            bt = channelTable(f.bMax(), f.bShift());
        }

        private int[] channelTable(int max, int shift) {
            int[] t = new int[256];
            for (int c = 0; c < 256; c++) t[c] = (c * max / 255) << shift;
            return t;
        }

        private void writeRaw(int[] px, int[] r, PixelFmt f) throws IOException {
            int rowBytes = r[2] * (f.bpp() / 8);
            if (row.length < rowBytes) row = new byte[rowBytes];
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                encodeRun(px, y * width + r[0], r[2], row, 0, f, false);
                out.write(row, 0, rowBytes);
            }
        }

        /** Converts n 0xAARRGGBB pixels to the client's format; returns the new offset in dst. */
        private int encodeRun(int[] px, int off, int n, byte[] dst, int o, PixelFmt f, boolean compact) {
            if (f.equals(PixelFmt.DEFAULT)) {
                if (compact) {
                    for (int i = off; i < off + n; i++) {
                        int p = px[i];
                        dst[o] = (byte) p;
                        dst[o + 1] = (byte) (p >> 8);
                        dst[o + 2] = (byte) (p >> 16);
                        o += 3;
                    }
                } else {
                    for (int i = off; i < off + n; i++) {
                        INT_LE.set(dst, o, px[i] | 0xff000000); // unused byte; opaque in case a viewer treats it as alpha
                        o += 4;
                    }
                }
                return o;
            }
            int bytes = compact ? 3 : f.bpp() / 8;
            boolean big = f.big();
            for (int i = off; i < off + n; i++) {
                int p = px[i];
                int v = rt[(p >> 16) & 0xff] | gt[(p >> 8) & 0xff] | bt[p & 0xff];
                if (big) {
                    for (int k = bytes - 1; k >= 0; k--) dst[o++] = (byte) (v >>> (8 * k));
                } else {
                    for (int k = 0; k < bytes; k++) dst[o++] = (byte) (v >>> (8 * k));
                }
            }
            return o;
        }

        // ---- ZRLE: 64x64 tiles (solid, packed palette or raw), one zlib stream for the whole connection

        private void writeZrle(int[] px, int[] r, PixelFmt f) throws IOException {
            boolean compact = f.compactCpixel();
            int cp = compact ? 3 : f.bpp() / 8;
            zinLen = 0;
            for (int ty = r[1]; ty < r[1] + r[3]; ty += TILE) {
                int th = Math.min(TILE, r[1] + r[3] - ty);
                for (int tx = r[0]; tx < r[0] + r[2]; tx += TILE) {
                    encodeTile(px, tx, ty, Math.min(TILE, r[0] + r[2] - tx), th, f, compact, cp);
                }
            }
            if (deflater == null) deflater = new Deflater(3);
            deflater.setInput(zin, 0, zinLen);
            zoutLen = 0;
            while (true) {
                if (zoutLen == zout.length) zout = Arrays.copyOf(zout, Math.max(1 << 16, zout.length * 2));
                zoutLen += deflater.deflate(zout, zoutLen, zout.length - zoutLen, Deflater.SYNC_FLUSH);
                if (zoutLen < zout.length) break;
            }
            out.writeInt(zoutLen);
            out.write(zout, 0, zoutLen);
        }

        private void zinEnsure(int extra) {
            if (zinLen + extra > zin.length) zin = Arrays.copyOf(zin, Math.max(zinLen + extra, zin.length * 2));
        }

        private void putCpixel(int argb, PixelFmt f, boolean compact, int cp) {
            zinEnsure(cp);
            one[0] = argb;
            zinLen = encodeRun(one, 0, 1, zin, zinLen, f, compact);
        }

        private void encodeTile(int[] px, int tx, int ty, int tw, int th, PixelFmt f, boolean compact, int cp) {
            int n = tw * th;
            for (int y = 0; y < th; y++) {
                System.arraycopy(px, (ty + y) * width + tx, tile, y * tw, tw);
            }
            int colours = 0;
            boolean paletteOk = true;
            for (int i = 0; i < n && paletteOk; i++) {
                int c = tile[i] & 0xffffff;
                int k = 0;
                while (k < colours && (palette[k] & 0xffffff) != c) k++;
                if (k == colours) {
                    if (colours == palette.length) {
                        paletteOk = false;
                        break;
                    }
                    palette[colours++] = tile[i];
                }
                tileIdx[i] = (byte) k;
            }
            if (paletteOk && colours == 1) {
                zinEnsure(1 + cp);
                zin[zinLen++] = 1;
                putCpixel(palette[0], f, compact, cp);
            } else if (paletteOk) {
                int bits = colours == 2 ? 1 : colours <= 4 ? 2 : 4;
                int rowBytes = (tw * bits + 7) / 8;
                zinEnsure(1 + colours * cp + rowBytes * th);
                zin[zinLen++] = (byte) colours;
                for (int k = 0; k < colours; k++) putCpixel(palette[k], f, compact, cp);
                for (int y = 0; y < th; y++) {
                    int acc = 0, used = 0;
                    for (int x = 0; x < tw; x++) {
                        acc = (acc << bits) | tileIdx[y * tw + x];
                        used += bits;
                        if (used == 8) {
                            zin[zinLen++] = (byte) acc;
                            acc = 0;
                            used = 0;
                        }
                    }
                    if (used > 0) zin[zinLen++] = (byte) (acc << (8 - used));
                }
            } else {
                zinEnsure(1 + n * cp);
                zin[zinLen++] = 0;
                zinLen = encodeRun(tile, 0, n, zin, zinLen, f, compact);
            }
        }
    }

    // ------------------------------------------------------------------ tile diff

    /** Changed tiles as {x, y, w, h}; horizontally adjacent changed tiles are merged into one rect. */
    private List<int[]> diff(int[] a, int[] b) {
        List<int[]> out = new ArrayList<>();
        int tilesX = (width + TILE - 1) / TILE;
        for (int ty = 0; ty < height; ty += TILE) {
            int th = Math.min(TILE, height - ty);
            int runStart = -1;
            for (int i = 0; i <= tilesX; i++) {
                int tx = i * TILE;
                boolean changed = i < tilesX && tileChanged(a, b, tx, ty, Math.min(TILE, width - tx), th);
                if (changed && runStart < 0) runStart = tx;
                if (!changed && runStart >= 0) {
                    out.add(new int[]{runStart, ty, Math.min(tx, width) - runStart, th});
                    runStart = -1;
                }
            }
        }
        return out;
    }

    private boolean tileChanged(int[] a, int[] b, int tx, int ty, int tw, int th) {
        for (int y = ty; y < ty + th; y++) {
            int off = y * width + tx;
            if (!Arrays.equals(a, off, off + tw, b, off, off + tw)) return true;
        }
        return false;
    }

    private static void info(String msg) {
        System.err.println("[vnc] " + msg);
    }
}
