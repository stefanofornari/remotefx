package spike.vnc;

import javafx.application.Application;

/**
 * Entry point that does not extend Application, so the JVM does not require JavaFX
 * as a named module when running from the classpath (needed for Monocle).
 */
public final class Launcher {

    public static void main(String[] args) {
        Application.launch(Demo.class, args);
    }
}
