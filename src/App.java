import javafx.application.Application;
import javafx.stage.Stage;
import ui.MainView;

/**
 * Entry point JavaFX của Lab 7.
 *
 * <p>Chạy: {@code java --module-path libs/javafx-sdk-21.0.5/lib
 * --add-modules javafx.controls,javafx.web -cp "out;libs/*" App}
 */
public final class App extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        System.setProperty("file.encoding", "UTF-8");
        new MainView().show(stage);
    }
}
