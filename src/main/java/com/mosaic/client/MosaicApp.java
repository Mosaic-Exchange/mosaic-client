
package com.mosaic.client;

import com.mosaic.client.db.DatabaseManager;
import com.mosaic.client.service.NetworkManager;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MosaicApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        AppConfig.writeDefaultIfMissing();
        AppConfig config = AppConfig.load();

        // Initialize the database (creates tables on first run)
        DatabaseManager.getInstance().initialize(config.dataDir());

        try {
            NetworkManager.getInstance().start(
                    config.host(),
                    config.port(),
                    config.llmServerPort(),
                    config.nodeType(),
                    config.debugEnabled(),
                    config.dataDir(),
                    config.logDir(),
                    config.seedAddresses());
        } catch (Exception e) {
            System.err.println("Failed to start network node: " + e.getMessage());
            // App remains usable for local-only inference
        }

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
    public void stop() {
        NetworkManager.getInstance().stop();
        DatabaseManager.getInstance().shutdown();
    }

    public static void main(String[] args) {
        String configFile = "mosaic.yml";
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configFile = args[i + 1];
                break;
            }
        }

        // Resolve relative to CWD
        try {
            configFile = Path.of(configFile).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            // Fallback to whatever was provided
        }

        AppConfig.setActiveConfigFilename(configFile);
        launch(args);
    }
}
