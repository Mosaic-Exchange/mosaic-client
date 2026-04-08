package com.mosaic.client;

/**
 * Entry point for the shaded / fat JAR. Must not extend
 * {@link javafx.application.Application} so {@code java -jar} works when JavaFX
 * is on the classpath inside the same archive.
 */
public final class MosaicLauncher {

    public static void main(String[] args) {
        MosaicApp.main(args);
    }

    private MosaicLauncher() {}
}
