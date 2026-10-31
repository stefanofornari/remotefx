package spike.vnc;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

import javafx.application.Platform;

/**
 * Java agent that attaches the VNC server to any JavaFX application without touching its code:
 *
 * <pre>
 * java -javaagent:remotefx.jar=port=5900,bind=0.0.0.0 ... -jar app.jar
 * </pre>
 *
 * Agent arguments are comma-separated {@code key=value} pairs, each becoming the system property
 * {@code vnc.<key>} (port, bind, capture, debug). The password is read from the VNC_PASSWORD
 * environment variable.
 */
public final class VncAgent {

    private VncAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        if (args != null) {
            for (String kv : args.split(",")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    System.setProperty("vnc." + kv.substring(0, i).trim(), kv.substring(i + 1).trim());
                }
            }
        }
        Thread t = new Thread(VncAgent::startWhenToolkitIsUp, "vnc-agent");
        t.setDaemon(true);
        t.start();
    }

    /** Platform.runLater throws IllegalStateException until the application has started the FX toolkit. */
    private static void startWhenToolkitIsUp() {
        int port = Integer.getInteger("vnc.port", 5900);
        while (true) {
            try {
                Platform.runLater(() -> {
                    try {
                        VncServer.start(port);
                    } catch (IOException | RuntimeException e) {
                        System.err.println("[vnc] agent could not start the server: " + e);
                    }
                });
                return;
            } catch (IllegalStateException toolkitNotReady) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
