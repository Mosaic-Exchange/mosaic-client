package com.mosaic.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MosaicApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
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

    public static void main(String[] args) {
        launch(args);
    }
}
