package spike.vnc;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javafx.application.Platform;
import javafx.stage.Window;

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
        openJavaFxInternals(inst);
        Thread t = new Thread(VncAgent::startWhenToolkitIsUp, "vnc-agent");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Monocle lives in the unnamed module (it is bundled in this jar) but extends and calls JavaFX
     * internals such as com.sun.glass.ui.PlatformFactory, which the javafx.* modules do not export.
     * When the application runs JavaFX from the module path this fails with an IllegalAccessError, so
     * export and open every package of the loaded javafx.* modules to the unnamed module here, instead
     * of asking the user for a list of --add-exports flags. Does nothing when JavaFX is on the classpath.
     */
    private static void openJavaFxInternals(Instrumentation inst) {
        Module unnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();
        for (Module m : ModuleLayer.boot().modules()) {
            if (!m.isNamed() || !m.getName().startsWith("javafx.") || !inst.isModifiableModule(m)) continue;
            Map<String, Set<Module>> packages = new HashMap<>();
            for (String p : m.getPackages()) {
                packages.put(p, Set.of(unnamed));
            }
            // (module, extraReads, extraExports, extraOpens, extraUses, extraProvides)
            inst.redefineModule(m, Set.of(), packages, packages, Set.of(), Map.of());
            if (Settings.bool("debug")) {
                System.err.println("[vnc] opened " + packages.size() + " packages of " + m.getName());
            }
        }
    }

    /**
     * Platform.runLater throws IllegalStateException until the application has started the FX toolkit.
     * Once it is up, we still wait for the first showing window, so capture never starts on an empty screen.
     */
    private static void startWhenToolkitIsUp() {
        int port = Settings.integer("port", 5900);
        while (true) {
            try {
                CompletableFuture<Boolean> shown = new CompletableFuture<>();
                Platform.runLater(() -> shown.complete(anyWindowShowing()));
                if (shown.get()) {
                    Platform.runLater(() -> {
                        try {
                            VncServer.start(port);
                        } catch (IOException | RuntimeException e) {
                            System.err.println("[vnc] agent could not start the server: " + e);
                        }
                    });
                    return;
                }
            } catch (IllegalStateException toolkitNotReady) {
                // toolkit not started yet: keep polling
            } catch (InterruptedException | ExecutionException e) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean anyWindowShowing() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) return true;
        }
        return false;
    }
}
