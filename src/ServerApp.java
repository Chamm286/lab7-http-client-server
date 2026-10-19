import javafx.application.Application;
import javafx.stage.Stage;
import ui.MainView;

public class ServerApp extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        new MainView().showServer(stage);
    }
}
