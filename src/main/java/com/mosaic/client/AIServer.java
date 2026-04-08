package com.mosaic.client;

import javafx.beans.property.*;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;


public class AIServer {
    public enum ServerState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    // Instance
    private static AIServer instance;

    // Observables
    private final StringProperty lastGenerated = new SimpleStringProperty();
    private final ObjectProperty<HealthCheckResult> lastHealthCheck = new SimpleObjectProperty<>(
            new HealthCheckResult(
                false,
                false,
                Optional.empty()
            )
    );
    private final ObjectProperty<ServerState> state = new SimpleObjectProperty<>(ServerState.DISCONNECTED);

    // File locations
    private final Path SERVER_DIR = FileSystems.getDefault().getPath("llm-server", "setup");
    private final Path PYTHON_BIN_FROM_SERVER_DIR = FileSystems.getDefault().getPath(
            "..", ".venv", "bin", "python"
    );
    private final Path LOG_FILE = FileSystems.getDefault().getPath("llmserver.log");

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

    private AIServer() throws URISyntaxException { }

    public static AIServer getInstance() {
        if (instance == null) {
            try {
                instance = new AIServer();
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
    public ReadOnlyObjectProperty<ServerState> stateProperty() { return state; }

    public boolean running() {
        return proc != null && proc.isAlive();
    }

    public void startServer(String host, int port) throws URISyntaxException, IOException {
        if (running()) { return; }

        state.setValue(ServerState.CONNECTING);

        System.getLogger("AIServer").log(
                System.Logger.Level.INFO,
                "Starting AI server at %s:%d. Logs will be directed to %s.".formatted(host, port, LOG_FILE.toString())
        );

        this.host = host;
        this.port = port;
        this.baseUri = new URI("http", "", this.host, this.port, "/", "", "");

        // Start the process
        proc = new ProcessBuilder(
                PYTHON_BIN_FROM_SERVER_DIR.toString(),
                "-m",
                "uvicorn",
                "middleware_server:app",
                "--host",
                host,
                "--port",
                String.valueOf(port)
        )
                .directory(this.SERVER_DIR.toFile())
                .redirectErrorStream(true)
                .redirectOutput(this.LOG_FILE.toFile())
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
                    state.setValue(ServerState.CONNECTED);
                } else if (newValue.error.isPresent() && state.get() == ServerState.CONNECTED) {
                    state.setValue(ServerState.DISCONNECTED);
                }
            }
        );
        lastHealthCheck.bind(healthMonitor.lastValueProperty());
        healthMonitor.start();
    }

    public void stopServer() {
        if (running()) {
            System.getLogger("AIServer").log(
                    System.Logger.Level.INFO,
                    "Stopping AI server at %s:%d. Logs can be found in %s.".formatted(host, port, LOG_FILE.toString())
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

    public Void generateResponse(
            String prompt,
            int maxTokens
    ) {
        return generateResponse(prompt, maxTokens, null);
    }

    /**
     * Send a request to the LLM.
     * @param prompt Input prompt.
     * @param maxTokens Input maximum tokens.
     * @param adapterId The adapter to use for the inference.
     */
    public Void generateResponse(
            String prompt,
            int maxTokens,
            String adapterId
    ) {
        ensureHttpClient();
        ensureNoConcurrentRequest();

        // Create a task to send the request
        Task<String> request = new Task<String>() {
            @Override
            protected String call() throws Exception {
                return generateResponseSync(
                    prompt,
                    maxTokens,
                    Optional.ofNullable(adapterId)
                );
            }
        };

        // Ensure this property is updated with the latest value upon completion
        lastGenerated.bind(request.valueProperty());

        // Run the task
        Thread th = new Thread(request);
        th.setDaemon(true);
        th.start();

        return null;
    }

    public record HealthCheckResult(
            boolean middlewareConnected,
            boolean llamaCppConnected,
            Optional<Exception> error
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
            System.getLogger("AIServer.healthCheck").log(
                    System.Logger.Level.ERROR,
                    "Middleware server unreachable for health check (%s).".formatted(e)
            );
            return new HealthCheckResult(false, false, Optional.of(e));
        }

        JSONObject responseBody = new JSONObject(response.body());

        return new HealthCheckResult(
                responseBody.getString("middleware").equals("ok"),
                responseBody.getString("llama_server").equals("ok"),
                Optional.empty()
        );
    }

    /**
     * Synchronous AI generation request, which generally should not be used. Called by generateResponse.
     */
    public final String generateResponseSync(
            String prompt,
            int maxTokens,
            Optional<String> adapterId
    ) throws IllegalCallerException, IOException, InterruptedException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("request_id", String.valueOf(getRequestId()));
        requestBody.put("message", prompt);
        requestBody.put("max_tokens", maxTokens);
        adapterId.ifPresent(s -> requestBody.put("adapter_id", s));

        URI target = this.baseUri.resolve(apiSpec.get(APIOperation.GENERATE));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(target)
                .method("POST", HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Accept-Encoding", "application/json")
                .header("Content-Type", "application/json")
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject responseBody = new JSONObject(response.body());

        return responseBody.getString("output");
    }
}
