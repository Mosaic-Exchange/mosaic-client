
package com.mosaic.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MosaicApp extends Application {

    private final ExchangeServerProcess exchangeServer = new ExchangeServerProcess();
    private final RumorClient           rumorClient    = new RumorClient();
    private final ConnectionMonitor     monitor        = new ConnectionMonitor(rumorClient);

    /**
     * Called on the launcher thread before the JavaFX stage is shown.
     * Starts the exchange-server subprocess, waits for readiness, and records the result
     * in Navigator so the splash screen can render the correct state immediately.
     */
    @Override
    public void init() throws Exception {
        boolean available = false;
        try {
            exchangeServer.start();
            available = exchangeServer.waitUntilReady();
        } catch (Exception e) {
            System.err.println("[MosaicApp] Could not start exchange server: " + e.getMessage());
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

        // Register the monitor before showSplash() so SplashController.initialize()
        // can subscribe to state-change callbacks.
        Navigator.setConnectionMonitor(monitor);
        controller.showSplash();

        monitor.start();
    }

    /**
     * Called when the JavaFX application is closing. Shuts down the monitor and exchange server.
     */
    @Override
    public void stop() {
        monitor.stop();
        exchangeServer.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
