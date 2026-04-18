package com.mosaic.client.service;

import javafx.beans.property.*;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;


public class LLMServer {
    public enum State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    // Instance
    private static LLMServer instance;

    // Observables
    private final StringProperty lastGenerated = new SimpleStringProperty();
    private final ObjectProperty<HealthCheckResult> lastHealthCheck = new SimpleObjectProperty<>(
            new HealthCheckResult(
                false,
                false,
                Optional.empty()
            )
    );
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.DISCONNECTED);

    // File locations
    private static final String LOG_NAME = "llmserver.log";
    private final Path SERVER_DIR = FileSystems.getDefault().getPath("llm-server");
    private Path logFile;

    // Configuration
    private final Duration healthCheckPeriod = Duration.seconds(1);

    // API paths
    enum APIOperation {
        GENERATE,
        ADD_ADAPTER,
        LIST_ADAPTERS,
        REMOVE_ADAPTER,
        HEALTH_CHECK
    }
    private final Map<APIOperation, URI> apiSpec = Map.of(
            APIOperation.GENERATE, new URI("v1/generations"),
            APIOperation.ADD_ADAPTER, new URI("v1/adapters"),
            APIOperation.LIST_ADAPTERS, new URI("v1/adapters"),
            APIOperation.REMOVE_ADAPTER, new URI("v1/adapters"),
            APIOperation.HEALTH_CHECK, new URI("health")
    );

    // Process management
    private Process proc;
    private String host;
    private int port;
    private ScheduledService<HealthCheckResult> healthMonitor;

    // HTTP
    private HttpClient httpClient;
    private URI baseUri;
    private int lastId = 0;
    private boolean processing = false;  // Lock on requests

    private LLMServer() throws URISyntaxException { }

    public static LLMServer getInstance() {
        if (instance == null) {
            try {
                instance = new LLMServer();
            } catch (URISyntaxException e) {
                System.getLogger("AIServer").log(
                        System.Logger.Level.ERROR,
                        "API endpoints contain malformed URIs."
                );
                e.printStackTrace();
            }
        }

        return instance;
    }

    public ReadOnlyStringProperty lastGeneratedProperty() { return lastGenerated; }
    public ReadOnlyObjectProperty<HealthCheckResult> lastHealthCheckProperty() { return lastHealthCheck; }
    public ReadOnlyObjectProperty<State> stateProperty() { return state; }

    public boolean running() {
        return proc != null && proc.isAlive();
    }

    public void start(String host, int port, HttpClient httpClient, Path logDir) throws URISyntaxException, IOException {
        if (running()) { return; }

        logFile = logDir.resolve(LOG_NAME);
        Files.createDirectories(logDir);

        state.setValue(State.CONNECTING);

        System.getLogger("AIServer").log(
                System.Logger.Level.INFO,
                "Starting AI server at %s:%d. Logs will be directed to %s.".formatted(host, port, logFile.toString())
        );

        this.httpClient = httpClient;
        this.host = host;
        this.port = port;
        this.baseUri = new URI("http", "", this.host, this.port, "/", "", "");

        // Start the process
        proc = new ProcessBuilder(
                "bash",
                SERVER_DIR.resolve("start-server.sh").toString(),
                "--host",
                host,
                "--port",
                String.valueOf(port)
        )
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();

        // Start health monitor
        healthMonitor = new ScheduledService<>() {
            @Override
            protected Task<HealthCheckResult> createTask() {
                return new Task<>() {
                    @Override
                    protected HealthCheckResult call() {
                        if (processing) {
                            // Current processing another request; don't update the value.
                            return getLastValue();
                        }
                        return healthCheck();
                    }
                };
            }
        };
        healthMonitor.setPeriod(healthCheckPeriod);
        healthMonitor.lastValueProperty().addListener(
            (observable, oldValue, newValue) -> {
                if (newValue.allGood()) {
                    state.setValue(State.CONNECTED);
                } else if (state.get().equals(State.CONNECTED)) {
                    state.setValue(State.DISCONNECTED);
                }
            }
        );
        lastHealthCheck.bind(healthMonitor.lastValueProperty());
        healthMonitor.start();
    }

    public void stop() {
        if (running()) {
            System.getLogger("AIServer").log(
                    System.Logger.Level.INFO,
                    "Stopping AI server at %s:%d. Logs can be found in %s.".formatted(host, port, logFile.toString())
            );
            proc.destroy();
        }
    }

    private void ensureHttpClient() {
        if (httpClient == null || httpClient.isTerminated()) {
            httpClient = HttpClient.newHttpClient();
        }
    }

    private void ensureNoConcurrentRequest() throws IllegalCallerException {
        if (this.processing) {
            throw new IllegalCallerException("Only one request can be sent to the AI server at a time.");
        }
    }

    private int getRequestId() {
        return this.lastId++;
    }

    public record HealthCheckResult(
            boolean middlewareConnected,
            boolean llamaCppConnected,
            Optional<String> error
    ) {
        public boolean allGood() { return middlewareConnected && llamaCppConnected && error.isEmpty(); }
    }

    public final HealthCheckResult healthCheck() {
        if ( !running() ) {
            return new HealthCheckResult(false, false, Optional.empty());
        }

        ensureHttpClient();
        ensureNoConcurrentRequest();

        URI target = this.baseUri.resolve(apiSpec.get(APIOperation.HEALTH_CHECK));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(target)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .header("Accept-Encoding", "application/json")
                .header("Content-Type", "application/json")
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (state.get().equals(State.CONNECTED)) {
                System.getLogger("AIServer.healthCheck").log(
                        System.Logger.Level.ERROR,
                        "Middleware server unreachable for health check (%s).".formatted(e)
                );
            }
            return new HealthCheckResult(false, false, Optional.of(e.toString()));
        }

        if (response.statusCode() != 200) {
            System.getLogger("AIServer.healthCheck").log(
                    System.Logger.Level.ERROR,
                    "Middleware server health check returned bad HTTP status (%s).".formatted(response.statusCode())
            );
            return new HealthCheckResult(false, false, Optional.of(response.toString()));
        } else if (
                lastHealthCheck.get().error().isPresent()
                        && lastHealthCheck.get().error().get().contains("Exception")
        ) {
            System.getLogger("AIServer.healthCheck").log(
                    System.Logger.Level.INFO,
                    "Connection (re)established (middleware server health check responded with HTTP 200 OK)."
            );
        }

        JSONObject responseBody = new JSONObject(response.body());

        return new HealthCheckResult(
                responseBody.getString("middleware").equals("ok"),
                responseBody.getString("llama_server").equals("ok"),
                Optional.empty()
        );
    }

}
