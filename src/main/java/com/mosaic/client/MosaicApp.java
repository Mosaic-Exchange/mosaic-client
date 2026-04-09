package com.mosaic.client;

import com.mosaic.client.db.DatabaseManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MosaicApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Initialize the database (creates tables on first run)
        DatabaseManager.getInstance().initialize();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainLayout.fxml"));
        Scene scene = new Scene(loader.load(), 1024, 700);
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());

        primaryStage.setTitle("Mosaic");
        primaryStage.setScene(scene);
        primaryStage.show();

        MainLayoutController controller = loader.getController();
        Navigator.init(controller);
        controller.showSplash();
    }

    @Override
    public void stop() throws Exception {
        // Clean shutdown of the database connection
        DatabaseManager.getInstance().shutdown();
        AIServer.getInstance().stopServer();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
