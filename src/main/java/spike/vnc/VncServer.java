package spike.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
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
    private int lastMask;
    private Stage activeWindow; // window of the last button press
    private boolean shift, ctrl, alt, meta;

    private ServerSocket serverSocket;
    private int width, height;

    // guarded by frameLock; a published pixel array is never modified afterwards
    private int[] framePixels;
    private long frameSeq;

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
        if (clients.isEmpty() || now - lastCaptureNanos < MIN_FRAME_NANOS) return;
        lastCaptureNanos = now;

        int[] px = useScenes ? captureScenes() : captureRobot();
        if (px == null) return;
        if (!useScenes && isBlank(px) && hasShowingWindow()) {
            if (++blankFrames >= 5) {
                info("robot capture is all black; switching to per-window scene snapshots (same as -Dvnc.capture=scene)");
                useScenes = true;
            }
            return;
        }
        if (DEBUG && frameSeq == 0) info("first frame captured (" + (useScenes ? "scene" : "robot") + ")");
        synchronized (frameLock) {
            framePixels = px;
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    /** Whole-screen grab through the platform robot; null if unusable. */
    private int[] captureRobot() {
        try {
            WritableImage img = robot.getScreenCapture(null, 0, 0, width, height);
            if ((int) img.getWidth() != width || (int) img.getHeight() != height) {
                info("capture is " + img.getWidth() + "x" + img.getHeight() + ", expected " + width + "x" + height + " (HiDPI scaling?)");
                return null;
            }
            int[] px = new int[width * height];
            img.getPixelReader().getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), px, 0, width);
            return px;
        } catch (RuntimeException e) {
            info("robot capture failed (" + e + "); switching to per-window scene snapshots");
            useScenes = true;
            return null;
        }
    }

    /** Fallback: snapshot every showing window (popups included) and paint it at its screen position. */
    private int[] captureScenes() {
        int[] px = new int[width * height];
        for (Window w : Window.getWindows()) {
            Scene s = w.getScene();
            if (s == null || !w.isShowing()) continue;
            WritableImage snap = s.snapshot(null);
            int sw = (int) snap.getWidth(), sh = (int) snap.getHeight();
            int[] sp = new int[sw * sh];
            snap.getPixelReader().getPixels(0, 0, sw, sh, WritablePixelFormat.getIntArgbInstance(), sp, 0, sw);
            int ox = (int) Math.round(w.getX() + s.getX());
            int oy = (int) Math.round(w.getY() + s.getY());
            for (int y = 0; y < sh; y++) {
                int dy = oy + y;
                if (dy < 0 || dy >= height) continue;
                for (int x = 0; x < sw; x++) {
                    int dx = ox + x;
                    if (dx < 0 || dx >= width) continue;
                    int p = sp[y * sw + x];
                    if ((p >>> 24) != 0) px[dy * width + dx] = p;
                }
            }
        }
        return px;
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

    private void pointer(int mask, int x, int y) {
        Platform.runLater(() -> {
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
        });
    }

    /** Topmost (most recently shown) application window containing the screen point, or null. */
    private static Stage windowAt(int x, int y) {
        List<Window> all = Window.getWindows();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof Stage s && s.isShowing() && s.getScene() != null
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

    /** Focused window, else the one last clicked, else the most recently shown one. */
    private Node focusTarget() {
        Window focused = null, lastShown = null;
        for (Window w : Window.getWindows()) {
            if (w.getScene() == null || !w.isShowing()) continue;
            if (w.isFocused()) {
                focused = w;
                break;
            }
            lastShown = w;
        }
        boolean activeUsable = activeWindow != null && activeWindow.isShowing() && activeWindow.getScene() != null;
        Window chosen = focused != null ? focused : activeUsable ? activeWindow : lastShown;
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

        /** Generic (slow) conversion from 0xAARRGGBB. */
        void encode(int argb, byte[] dst, int off) {
            int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
            int v = ((r * rMax / 255) << rShift) | ((g * gMax / 255) << gShift) | ((b * bMax / 255) << bShift);
            int n = bpp / 8;
            for (int i = 0; i < n; i++) {
                dst[off + i] = (byte) (big ? v >>> (8 * (n - 1 - i)) : v >>> (8 * i));
            }
        }
    }

    // ------------------------------------------------------------------ per-client protocol

    private final class Client implements Runnable {
        private final Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private volatile PixelFmt pf = PixelFmt.DEFAULT;

        // guarded by frameLock
        private boolean pending, forceFull, closed;

        // sender thread only
        private int[] prev;
        private long lastSeq;

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
                    case 2 -> { // SetEncodings (we only ever send Raw)
                        in.skipNBytes(1);
                        int n = in.readUnsignedShort();
                        in.skipNBytes(4L * n);
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
                    int[] cur;
                    long seq;
                    boolean full;
                    synchronized (frameLock) {
                        while (!closed && !(pending && framePixels != null && (forceFull || frameSeq != lastSeq))) {
                            frameLock.wait(1000);
                        }
                        if (closed) return;
                        cur = framePixels;
                        seq = frameSeq;
                        full = forceFull;
                        forceFull = false;
                    }
                    List<int[]> rects = (full || prev == null)
                            ? List.of(new int[]{0, 0, width, height})
                            : diff(prev, cur);
                    lastSeq = seq;
                    if (rects.isEmpty()) continue; // nothing changed: keep the request pending
                    synchronized (frameLock) {
                        pending = false;
                    }
                    writeUpdate(cur, rects, pf);
                    prev = cur;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (DEBUG) info("send failed: " + e);
            } finally {
                closeQuietly();
            }
        }

        private void writeUpdate(int[] px, List<int[]> rects, PixelFmt f) throws IOException {
            boolean fast = f.equals(PixelFmt.DEFAULT);
            if (DEBUG) info("sending update: " + rects.size() + " rect(s), format " + f);
            int bytesPP = f.bpp() / 8;
            byte[] row = new byte[width * 4];

            out.writeByte(0); // FramebufferUpdate
            out.writeByte(0);
            out.writeShort(rects.size());
            for (int[] r : rects) {
                out.writeShort(r[0]);
                out.writeShort(r[1]);
                out.writeShort(r[2]);
                out.writeShort(r[3]);
                out.writeInt(0); // Raw
                for (int y = r[1]; y < r[1] + r[3]; y++) {
                    int o = 0;
                    for (int x = r[0]; x < r[0] + r[2]; x++) {
                        int p = px[y * width + x];
                        if (fast) {
                            row[o] = (byte) p;
                            row[o + 1] = (byte) (p >> 8);
                            row[o + 2] = (byte) (p >> 16);
                            row[o + 3] = (byte) 0xff; // unused byte; opaque in case a viewer treats it as alpha
                            o += 4;
                        } else {
                            f.encode(p, row, o);
                            o += bytesPP;
                        }
                    }
                    out.write(row, 0, o);
                }
            }
            out.flush();
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
