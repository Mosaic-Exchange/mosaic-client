package com.mosaic.client.service;

import javafx.beans.property.*;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.File;
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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


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
        HEALTH_CHECK,
        SHUTDOWN
    }
    private final Map<APIOperation, URI> apiSpec = Map.of(
            APIOperation.GENERATE, new URI("v1/generations"),
            APIOperation.ADD_ADAPTER, new URI("v1/adapters"),
            APIOperation.LIST_ADAPTERS, new URI("v1/adapters"),
            APIOperation.REMOVE_ADAPTER, new URI("v1/adapters/"),
            APIOperation.HEALTH_CHECK, new URI("health"),
            APIOperation.SHUTDOWN, new URI("shutdown")
    );

    // Process management
    private Process proc;
    private String host;
    private int port;
    private ScheduledService<HealthCheckResult> healthMonitor;

    // HTTP
    private HttpClient httpClient;
    private URI baseUri;
    private final ReentrantLock lock = new ReentrantLock();
    private int lastId = 0;

    // Counter for unique request IDs, do not access directly (use getNewRequestId()).
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

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
                        if (lock.isLocked()) {
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
        if (!running()) return;

        System.getLogger("AIServer").log(
                System.Logger.Level.INFO,
                "Stopping AI server at %s:%d. Logs can be found in %s.".formatted(host, port, logFile.toString())
        );

        if (healthMonitor != null) {
            healthMonitor.cancel();
            healthMonitor = null;
        }

        try {
            ensureHttpClient();
            URI target = baseUri.resolve(apiSpec.get(APIOperation.SHUTDOWN));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Server may already be down; fall through to force-kill
        }

        try {
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (running()) {
            proc.destroy();
        }
    }

    private void ensureHttpClient() {
        if (httpClient == null || httpClient.isTerminated()) {
            httpClient = HttpClient.newHttpClient();
        }
    }

    private void ensureNoConcurrentRequest() throws IllegalCallerException {
        if (this.lock.isLocked()) {
            throw new IllegalCallerException("Only one request can be sent to the AI server at a time.");
        }
    }

    private synchronized String getNewRequestId() {
        return String.valueOf(requestIdCounter.getAndIncrement());
    }

    private Path getAdaptersDir() {
        return SERVER_DIR.resolve("adapters");
    }

    public void registerAdapter(Path newAdapterDir, Consumer<AddAdapterResponse> onComplete, Consumer<Throwable> onFailure) {
        Task<AddAdapterResponse> registrationTask = new Task<>() {
            @Override
            protected AddAdapterResponse call() throws Exception {
                File targetDir = newAdapterTargetDir(newAdapterDir.toFile());

                try {
                    FileUtils.copyDirectory(newAdapterDir.toFile(), targetDir);
                } catch (IOException e) {
                    return new AddAdapterResponse(null, null, Optional.of("Failed to copy adapter: " + e.getMessage()));
                }

                if (!targetDir.exists()) {
                    return new AddAdapterResponse(null, null, Optional.of("Failed to copy adapter: File missing after copy"));
                }

                return addAdapter(targetDir.getName());
            }
        };

        registrationTask.setOnSucceeded(event -> {
            if (onComplete != null) {
                onComplete.accept(registrationTask.getValue());
            }
        });

        registrationTask.setOnFailed(event -> {
            if (onFailure != null) {
                onFailure.accept(registrationTask.getException());
            }
        });

        new Thread(registrationTask).start();
    }

    public void deregisterAdapter(String serverSideId, Consumer<Void> onComplete, Consumer<Throwable> onFailure) {
        Task<Void> deregistrationTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                removeAdapter(serverSideId);
                return null;
            }
        };

        deregistrationTask.setOnSucceeded(event -> {
            if (onComplete != null) {
                onComplete.accept(deregistrationTask.getValue());
            }
        });

        deregistrationTask.setOnFailed(event -> {
            if (onFailure != null) {
                onFailure.accept(deregistrationTask.getException());
            }
        });

        new Thread(deregistrationTask).start();
    }

    private File newAdapterTargetDir(File newAdapterDir) {
        Path adaptersDir = getAdaptersDir();
        String originalName = newAdapterDir.getName();
        File targetDir = adaptersDir.resolve(originalName).toFile();

        // Check for collisions
        if (targetDir.exists()) {
            int counter = 1;
            while (targetDir.exists()) {
                String newName = originalName + "-" + counter;
                targetDir = adaptersDir.resolve(newName).toFile();
                counter++;
            }
        }
        return targetDir;
    }

    public record AddAdapterResponse(
            String adapterId,
            String adapterFilename,
            Optional<String> error
    ) {}

    public final AddAdapterResponse addAdapter(String adapterDir) {
        if (!running()) {
            return new AddAdapterResponse(null, null, Optional.of("Server not running"));
        }

        ensureHttpClient();
        ensureNoConcurrentRequest();

        lock.lock();
        try {
            URI target = this.baseUri.resolve(apiSpec.get(APIOperation.ADD_ADAPTER));
            JSONObject requestBody = new JSONObject();
            requestBody.put("adapter_dir", adapterDir);
            requestBody.put("request_id", getNewRequestId());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.getLogger("AIServer.addAdapter").log(
                        System.Logger.Level.ERROR,
                        "Middleware server addAdapter returned bad HTTP status (%s). Body: %s"
                                .formatted(response.statusCode(), response.body())
                );
                return new AddAdapterResponse(null, null,
                        Optional.of("HTTP %d. Response body:\n%s".formatted(response.statusCode(), response.body())));
            }

            JSONObject responseBody = new JSONObject(response.body());
            return new AddAdapterResponse(
                    responseBody.getString("adapter_id"),
                    responseBody.getString("adapter_filename"),
                    Optional.empty()
            );
        } catch (Exception e) {
            System.getLogger("AIServer.addAdapter").log(
                    System.Logger.Level.ERROR,
                    "Error sending request to middleware server (%s).".formatted(e)
            );
            return new AddAdapterResponse(null, null, Optional.of(e.toString()));
        } finally {
            lock.unlock();
        }
    }

    public final void removeAdapter(String serverSideId) throws IOException, InterruptedException, URISyntaxException {
        if (!running()) {
            throw new IllegalStateException("Attempted to delete adapter while server not running.");
        }

        ensureHttpClient();
        ensureNoConcurrentRequest();

        lock.lock();
        try {
            URI target = this.baseUri.resolve(apiSpec.get(APIOperation.REMOVE_ADAPTER));
            target = target.resolve(new URI(serverSideId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target)
                    .DELETE()
                    .header("Accept", "application/json")
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.getLogger("AIServer.addAdapter").log(
                        System.Logger.Level.ERROR,
                        "Middleware server addAdapter returned bad HTTP status (%s). Body: %s"
                                .formatted(response.statusCode(), response.body())
                );
                throw new RuntimeException("Delete adapter operation fail (HTTP %d).".formatted(response.statusCode()));
            }

            JSONObject responseBody = new JSONObject(response.body());

            if (!responseBody.getString("adapter_id").equals(serverSideId)) {
                throw new RuntimeException("Delete adapter operation failed: Response adapter ID does not match request.");
            }
        } finally {
            lock.unlock();
        }
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

        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

}
