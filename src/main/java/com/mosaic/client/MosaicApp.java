package com.mosaic.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.mosaic.client.config.AppConfig;

public class MosaicApp extends Application {

    private final NetworkManager networkManager = new NetworkManager();

    /**
     * Called on the launcher thread before the JavaFX stage is shown.
     * Starts the local Rumor node and records availability in Navigator so the
     * splash screen can render the correct state immediately.
     *
     * TODO: read localHost / localPort / seedHost / seedPort from the Settings screen
     *       once that screen supports persisting network configuration.
     */
    @Override
    public void init() throws Exception {
        AppConfig config = new AppConfig();
        config.load();

        String localHost = config.getMyIp();
        int    localPort = config.getMyPortAsInt();
        String seedHost  = config.getSeedIp();
        int    seedPort  = config.getSeedPortAsInt();

        boolean available = false;
        try {
            networkManager.start(localHost, localPort, seedHost, seedPort);
            available = true;
        } catch (Exception e) {
            System.err.println("[MosaicApp] Could not start Rumor node: " + e.getMessage());
        }
        Navigator.setNetworkAvailable(available);
    }

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

        // Register the network manager before showSplash() so any controller's
        // initialize() can subscribe to callbacks immediately.
        Navigator.setNetworkManager(networkManager);
        controller.showSplash();
    }

    /**
     * Called when the JavaFX application is closing. Shuts down the Rumor node.
     */
    @Override
    public void stop() {
        networkManager.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
