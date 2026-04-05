package com.mosaic.client;

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
    // Default file locations
    private final Path DEFAULT_SERVER_DIR = FileSystems.getDefault().getPath("llmserver");
    private final Path DEFAULT_LOG_FILE = FileSystems.getDefault().getPath("llmserver.log");

    // Base command used to start the server (lacking host/port)
    private final String BASE_START_COMMAND = "pipenv run python -m uvicorn middleware_server:app";

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
    private Path serverDir, logFile;
    private Process proc;
    private String host;
    private int port;

    // HTTP
    private HttpClient httpClient;
    private URI baseUri;
    private int lastId = 0;
    private boolean processing = false;  // Lock on requests

    public AIServer(Optional<Path> serverDir, Optional<Path> logFile) throws URISyntaxException {
        this.serverDir = serverDir.orElse(DEFAULT_SERVER_DIR);
        this.logFile = serverDir.orElse(DEFAULT_LOG_FILE);
    }

    public boolean running() {
        return proc != null && proc.isAlive();
    }

    public void startServer(String host, int port) throws URISyntaxException, IOException {
        if (running()) { return; }

        this.host = host;
        this.port = port;
        this.baseUri = new URI("http", "", this.host, this.port, "/", "", "");

        // Start the process
//        proc = new ProcessBuilder(BASE_START_COMMAND, "--host", host, "--port", String.valueOf(port))
//                .directory(this.serverDir.toFile())
//                .redirectErrorStream(true)
//                .redirectOutput(this.logFile.toFile())
//                .start();
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

    public String generateResponse(
            String message,
            int maxTokens,
            Optional<String> adapterId
    ) throws IllegalCallerException, IOException, InterruptedException {
        ensureHttpClient();
        ensureNoConcurrentRequest();

        JSONObject requestBody = new JSONObject();
        requestBody.put("request_id", String.valueOf(getRequestId()));
        requestBody.put("message", message);
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

        // TODO: Add response format validation
        JSONObject responseBody = new JSONObject(response.body());

        return responseBody.getString("output");

//        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
