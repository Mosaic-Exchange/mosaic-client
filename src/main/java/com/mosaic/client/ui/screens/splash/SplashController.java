package com.mosaic.client.ui.screens.splash;

import com.mosaic.client.AIServer;
import com.mosaic.client.Navigator;
import javafx.fxml.FXML;

public class SplashController {

    // TODO APP-2 (#6): Add logo, status panel, and loading indicator per design doc (AC1, AC3)
    private static boolean started = false;

    @FXML
    private void onGetStarted() {
        if (started) { return; }

        // Start the AI server
        try {
            AIServer.getInstance().startServer("127.0.0.1", 4000);
            started = true;
        } catch (Exception e) {
            System.getLogger("SplashController").log(
                    System.Logger.Level.ERROR,
                    "Starting the server produced an error. Details:"
            );
            e.printStackTrace();
            // TODO: display some error to the user
        }

        if (started) {
            Navigator.showWorkspace();
        }
    }
}
