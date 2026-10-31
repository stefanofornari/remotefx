package spike.vnc;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Small UI to poke at through a VNC viewer, with the VncServer attached. */
public class Demo extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Label counter = new Label("clicks: 0");
        Button button = new Button("Click me");
        int[] n = {0};
        button.setOnAction(e -> counter.setText("clicks: " + (++n[0])));

        TextField field = new TextField();
        field.setPromptText("type here (try è, à, @, #)");
        Label echo = new Label();
        echo.textProperty().bind(Bindings.concat("text: ", field.textProperty()));

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("One", "Two", "Three"));
        combo.getSelectionModel().selectFirst();

        CheckBox check = new CheckBox("A checkbox");

        Slider slider = new Slider(0, 100, 30);
        ProgressBar bar = new ProgressBar();
        bar.progressProperty().bind(slider.valueProperty().divide(100));

        TextArea area = new TextArea("Scroll me with the wheel.\n".repeat(30));

        VBox root = new VBox(10, counter, button, field, echo, combo, check, slider, bar, area);
        root.setPadding(new Insets(16));

        // fill the (virtual) screen so the VNC view shows just the app
        Rectangle2D b = Screen.getPrimary().getVisualBounds();
        stage.setScene(new Scene(root));
        stage.setTitle("Monocle VNC demo");
        stage.setX(b.getMinX());
        stage.setY(b.getMinY());
        stage.setWidth(b.getWidth());
        stage.setHeight(b.getHeight());
        stage.show();

        if (!Boolean.getBoolean("demo.noserver")) { // run-agent.sh attaches the server with the Java agent instead
            VncServer.start(Integer.getInteger("vnc.port", 5900));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
