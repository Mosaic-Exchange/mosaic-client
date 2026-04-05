
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
     * Starts the exchange-server subprocess and waits until it is ready.
     */
    @Override
    public void init() throws Exception {
        try {
            exchangeServer.start();
            exchangeServer.waitUntilReady();
        } catch (Exception e) {
            System.err.println("[MosaicApp] Could not start exchange server: " + e.getMessage());
        }
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
        controller.showSplash();

        monitor
          .onConnectionStateChanged(state ->
              System.out.println("[Monitor] connection state -> " + state))
          .onClusterChanged(nodes ->
              System.out.println("[Monitor] cluster (" + nodes.size() + "): " + nodes))
          .onPeerStatusChanged((id, st) ->
              System.out.println("[Monitor] peer " + id + " -> " + st));

        monitor.start();
    }

    /**
     * Called when the JavaFX application is closing. Shuts down the exchange-server subprocess.
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
