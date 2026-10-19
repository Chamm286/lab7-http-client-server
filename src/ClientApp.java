import javafx.application.Application;
import javafx.stage.Stage;
import ui.MainView;

public class ClientApp extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        new MainView().showClient(stage);
    }
}
