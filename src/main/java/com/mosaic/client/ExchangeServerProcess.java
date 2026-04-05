package com.mosaic.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the lifecycle of the exchange-server subprocess.
 * Launches the JAR, waits for it to become ready, and shuts it down on app exit.
 */
public class ExchangeServerProcess {

    private static final String JAR_RELATIVE_PATH = "exchange-server/target/rumor-1.0-SNAPSHOT.jar";
    private static final int TCP_PORT = 7001;
    private static final int HTTP_PORT = 8080;
    private static final String HEALTH_ENDPOINT = "http://localhost:" + HTTP_PORT + "/api/debug";
    private static final int MAX_WAIT_MS = 10_000;
    private static final int POLL_INTERVAL_MS = 500;

    private Process process;

    /**
     * Starts the exchange-server subprocess.
     *
     * @throws FileNotFoundException if the JAR has not been built yet
     * @throws IOException           if the process cannot be launched
     */
    public void start() throws IOException {
        Path jarPath = resolveJarPath();

        if (!jarPath.toFile().exists()) {
            throw new FileNotFoundException(
                "Exchange server JAR not found at: " + jarPath.toAbsolutePath() + "\n" +
                "Please build it first by running inside the exchange-server directory:\n" +
                "  mvn package -DskipTests"
            );
        }

        ProcessBuilder pb = new ProcessBuilder(
            "java", "-jar", jarPath.toAbsolutePath().toString(),
            "--port",      String.valueOf(TCP_PORT),
            "--type",      "master",
            "--http-port", String.valueOf(HTTP_PORT)
        );

        // Merge stderr into stdout and inherit the parent process's stdout
        // so exchange-server logs are visible in the same console.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        process = pb.start();
        System.out.println("[ExchangeServerProcess] Started (PID " + process.pid() + ")"
            + " — tcp=" + TCP_PORT + " http=" + HTTP_PORT);
    }

    /**
     * Blocks until the exchange-server's HTTP endpoint responds (up to ~10 s).
     *
     * @return {@code true} if the server became ready within the timeout, {@code false} otherwise
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean waitUntilReady() throws InterruptedException {
        System.out.println("[ExchangeServerProcess] Waiting for server at " + HEALTH_ENDPOINT + " …");
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (isServerReady()) {
                System.out.println("[ExchangeServerProcess] Server is ready.");
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        System.err.println("[ExchangeServerProcess] WARNING: Server did not respond within "
            + (MAX_WAIT_MS / 1000) + " s — continuing anyway.");
        return false;
    }

    /**
     * Destroys the subprocess if it is still running.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            System.out.println("[ExchangeServerProcess] Exchange server stopped.");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isServerReady() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(HEALTH_ENDPOINT).openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolveJarPath() {
        // user.dir is the project root when launched via "mvn javafx:run"
        String workingDir = System.getProperty("user.dir");
        return Paths.get(workingDir, JAR_RELATIVE_PATH);
    }
}
