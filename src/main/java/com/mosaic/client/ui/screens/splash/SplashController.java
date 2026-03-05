package com.mosaic.client.ui.screens.splash;

import com.mosaic.client.Navigator;
import javafx.fxml.FXML;

public class SplashController {

    // TODO APP-2 (#6): Add logo, status panel, and loading indicator per design doc (AC1, AC3)

    @FXML
    private void onGetStarted() {
        Navigator.showWorkspace();
    }
}
