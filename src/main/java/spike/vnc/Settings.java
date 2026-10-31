package spike.vnc;

import java.util.Locale;

/**
 * Where an option comes from, in order: system property {@code vnc.<key>} (also set by the agent
 * arguments), environment variable {@code VNC_<KEY>}, then the default. No JavaFX types here, so
 * the agent can use it before JavaFX is loaded.
 */
final class Settings {

    private Settings() {
    }

    static String get(String key, String def) {
        String p = System.getProperty("vnc." + key);
        if (p != null) return p;
        String e = System.getenv("VNC_" + key.toUpperCase(Locale.ROOT));
        return e != null && !e.isEmpty() ? e : def;
    }

    static boolean bool(String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }

    static int integer(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
