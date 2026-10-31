package spike.vnc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Minimal window policy for a single application on a screen with no window manager (Monocle):
 *
 * <ul>
 *   <li>Top-level stages (no owner, not modal): exactly one is visible at a time and fills the screen.
 *       The others are parked just outside the right edge, so they are neither captured nor clickable.
 *       A newly shown top-level becomes the visible one.</li>
 *   <li>When there are two or more top-level stages, a tab strip along the top lets the user switch
 *       between them or close one. With a single top-level there is no chrome at all.</li>
 *   <li>Dialogs (owned or modal stages) keep their size and are centred in the content area. If a dialog's
 *       owner is parked, its top-level is brought back first.</li>
 *   <li>Popups (menus, tooltips, combo boxes) are not stages and are left alone.</li>
 * </ul>
 *
 * FX application thread only.
 */
final class WindowManager {

    private static final double STRIP_HEIGHT = 28;

    private final double screenW;
    private final double screenH;
    private final Consumer<Stage> onActivate;
    private final List<Stage> topLevels = new ArrayList<>();
    private final Set<Stage> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private final Stage strip = new Stage(StageStyle.UNDECORATED);
    private final HBox tabs = new HBox(2);
    private Stage visible;

    /** @param onActivate called with the stage that should receive keyboard input after each change */
    WindowManager(Consumer<Stage> onActivate) {
        this.onActivate = onActivate;
        Rectangle2D b = Screen.getPrimary().getBounds();
        screenW = b.getWidth();
        screenH = b.getHeight();

        tabs.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 2 2 0 2;");
        strip.setTitle("remotefx");
        strip.setScene(new Scene(tabs));

        // The agent starts after the application's first windows are up: adopt what is already showing.
        for (Window w : new ArrayList<>(Window.getWindows())) adopt(w);
        if (!topLevels.isEmpty()) show(topLevels.get(0)); // start on the first (main) window

        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    for (Window w : new ArrayList<>(c.getRemoved())) removed(w);
                }
                if (c.wasAdded()) {
                    // later, so that show() has finished and the stage has its size
                    for (Window w : new ArrayList<>(c.getAddedSubList())) Platform.runLater(() -> adopt(w));
                }
            }
        });
    }

    /** True for the tab strip, which must never receive application input. */
    boolean isInternal(Window w) {
        return w == strip;
    }

    // ------------------------------------------------------------------ keyboard shortcuts

    private static final int NONE = -1;
    private static final int CYCLE = -2;

    /**
     * The characters the viewer sends for Shift+0 ... Shift+9 (it sends the resulting character, not the
     * key). Default is the US layout; Italian, for example, is {@code =!"£$%&/()}.
     */
    private final String shiftedDigits = Settings.get("digits", ")!@#$%^&*(");

    /**
     * Ctrl+Shift+digit switches to the tab with that number, Ctrl+Shift+Tab to the next window.
     *
     * @return true if it switched windows, so the key must not reach the application
     */
    boolean handleShortcut(int keysym) {
        int target = shortcutTarget(keysym, shiftedDigits);
        if (target == CYCLE) return cycle();
        return target >= 0 && switchTo(target);
    }

    /** A zero-based tab index, {@link #CYCLE}, or {@link #NONE}. 1 is the first tab and 0 the tenth. */
    static int shortcutTarget(int keysym, String shiftedDigits) {
        if (keysym == 0xff09 || keysym == 0xfe20) return CYCLE; // Tab, ISO_Left_Tab
        int digit = -1;
        if (keysym >= '0' && keysym <= '9') {
            digit = keysym - '0';
        } else if (keysym > 0 && keysym < 0x10000) {
            int p = shiftedDigits.indexOf((char) keysym);
            if (p >= 0 && p <= 9) digit = p;
        }
        return digit < 0 ? NONE : digit == 0 ? 9 : digit - 1;
    }

    private boolean switchTo(int index) {
        if (index >= topLevels.size()) return false;
        show(topLevels.get(index));
        return true;
    }

    private boolean cycle() {
        if (topLevels.size() < 2) return false;
        int i = topLevels.indexOf(visible);
        show(topLevels.get((i + 1) % topLevels.size()));
        return true;
    }

    // ------------------------------------------------------------------ events

    private void adopt(Window w) {
        if (!(w instanceof Stage s) || s == strip || !s.isShowing()) return;

        if (isDialog(s)) {
            Stage root = rootTopLevel(s);
            if (root != null && root != visible) {
                visible = root;
                relayout();
            }
            center(s);
            onActivate.accept(s);
            return;
        }

        if (topLevels.contains(s)) return;
        topLevels.add(s);
        if (watched.add(s)) {
            s.titleProperty().addListener(o -> {
                if (strip.isShowing()) refreshStrip();
            });
        }
        visible = s; // the newest top-level wins
        relayout();
        onActivate.accept(s);
    }

    private void removed(Window w) {
        if (!(w instanceof Stage s) || s == strip) return;
        if (topLevels.remove(s) && visible == s) {
            visible = topLevels.isEmpty() ? null : topLevels.get(topLevels.size() - 1);
        }
        relayout();
        if (visible != null) onActivate.accept(visible);
    }

    private void show(Stage t) {
        if (!topLevels.contains(t)) return;
        visible = t;
        relayout();
        t.requestFocus(); // clicking the strip took the focus away
        onActivate.accept(t);
    }

    // ------------------------------------------------------------------ policy

    private static boolean isDialog(Stage s) {
        return s.getOwner() != null || s.getModality() != Modality.NONE;
    }

    /** The top-level stage a dialog belongs to (following the owner chain), or null. */
    private Stage rootTopLevel(Stage s) {
        Window w = s;
        while (w instanceof Stage st && st.getOwner() != null) w = st.getOwner();
        return w instanceof Stage r && topLevels.contains(r) ? r : null;
    }

    private double contentTop() {
        return topLevels.size() >= 2 ? STRIP_HEIGHT : 0;
    }

    private void relayout() {
        double top = contentTop();
        for (Stage t : topLevels) {
            if (t == visible) {
                t.setX(0);
                t.setY(top);
                t.setWidth(screenW);
                t.setHeight(screenH - top);
            } else {
                t.setX(screenW + 100); // parked: entirely outside the screen
                t.setY(0);
            }
        }
        if (top > 0) {
            strip.setX(0);
            strip.setY(0);
            strip.setWidth(screenW);
            strip.setHeight(STRIP_HEIGHT);
            if (!strip.isShowing()) strip.show();
            refreshStrip();
        } else if (strip.isShowing()) {
            strip.hide();
        }
        if (visible != null) visible.toFront();
    }

    private void center(Stage d) {
        double w = d.getWidth();
        double h = d.getHeight();
        if (Double.isNaN(w) || Double.isNaN(h)) return;
        double top = contentTop();
        d.setX(Math.max(0, (screenW - w) / 2));
        d.setY(Math.max(top, top + (screenH - top - h) / 2));
    }

    // ------------------------------------------------------------------ tab strip

    private void refreshStrip() {
        tabs.getChildren().clear();
        for (int i = 0; i < topLevels.size(); i++) {
            Stage t = topLevels.get(i);
            String title = t.getTitle() == null || t.getTitle().isBlank() ? "(untitled)" : t.getTitle();
            boolean active = t == visible;

            Button tab = new Button(number(i) + title);
            tab.setMaxWidth(260);
            tab.setStyle(style(active, "3 8 3 10"));
            tab.setOnAction(e -> show(t));

            Button close = new Button("×"); // multiplication sign: in every font
            close.setStyle(style(active, "3 7 3 6"));
            close.setOnAction(e -> t.fireEvent(new WindowEvent(t, WindowEvent.WINDOW_CLOSE_REQUEST)));

            tabs.getChildren().add(new HBox(0, tab, close));
        }
    }

    /** Tab label prefix: 1..9, then 0 for the tenth (the order of the digit row), none after that. */
    private static String number(int index) {
        return index < 9 ? (index + 1) + " " : index == 9 ? "0 " : "";
    }

    private static String style(boolean active, String padding) {
        return "-fx-background-color: " + (active ? "#4a6fa5" : "#3a3a3a") + ";"
                + " -fx-text-fill: white; -fx-background-radius: 0; -fx-font-size: 12px;"
                + " -fx-padding: " + padding + ";";
    }
}
