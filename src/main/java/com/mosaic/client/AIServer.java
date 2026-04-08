package com.mosaic.client;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
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
    // Instance
    private static AIServer instance;

    // Observables
    private final SimpleStringProperty lastGenerated = new SimpleStringProperty();

    // File locations
    private final Path SERVER_DIR = FileSystems.getDefault().getPath("llm-server", "setup");
    private final Path PYTHON_BIN_FROM_SERVER_DIR = FileSystems.getDefault().getPath(
            "..", ".venv", "bin", "python"
    );
    private final Path LOG_FILE = FileSystems.getDefault().getPath("llmserver.log");

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

    public boolean running() {
        return proc != null && proc.isAlive();
    }

    public void startServer(String host, int port) throws URISyntaxException, IOException {
        if (running()) { return; }

        System.getLogger("AIServer").log(
                System.Logger.Level.INFO,
                "Starting server at %s:%d. Logs will be directed to %s.".formatted(host, port, LOG_FILE.toString())
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
    }

    public ReadOnlyStringProperty lastGeneratedProperty() { return lastGenerated; }

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
