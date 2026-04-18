package com.mosaic.client.service;

import org.json.JSONObject;
import org.rumor.service.DistributedService;
import org.rumor.service.ServiceRequest;
import org.rumor.service.ServiceResponse;
import org.rumor.service.Streamable;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * LLM inference service backed by an Ollama HTTP endpoint.
 *
 * <p>Streams tokens as raw {@code byte[]} chunks so callers can display them
 * incrementally. Designed for portability: when the llm-server backend is
 * ready, swap the HTTP call inside {@code serve()} without changing the
 * Rumor service contract or any caller code.
 */
@Streamable
public class InferenceService extends DistributedService<InferenceRequest, byte[]> {

    private final URI endpoint;
    private final HttpClient httpClient;

    // API paths
    enum APIOperation {
        GENERATE,
        GENERATE_STREAM,
        ADD_ADAPTER,
        LIST_ADAPTERS,
        REMOVE_ADAPTER,
        HEALTH_CHECK
    }
    private final Map<APIOperation, URI> apiSpec = Map.of(
            APIOperation.GENERATE, new URI("v1/generations"),
            APIOperation.GENERATE_STREAM, new URI("v1/generations/stream"),
            APIOperation.ADD_ADAPTER, new URI("v1/adapters"),
            APIOperation.LIST_ADAPTERS, new URI("v1/adapters"),
            APIOperation.REMOVE_ADAPTER, new URI("v1/adapters"),
            APIOperation.HEALTH_CHECK, new URI("health")
    );

    public InferenceService() throws URISyntaxException {
        this("http://127.0.0.1:4000");
    }

    public InferenceService(String endpoint) throws URISyntaxException {
        this.endpoint = new URI(endpoint);
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void serve(ServiceRequest<InferenceRequest> request, ServiceResponse<byte[]> response) {
        InferenceRequest req = request.data();

        JSONObject requestBody = new JSONObject();
        requestBody.put("request_id", String.valueOf(req.request_id()));
        requestBody.put("message", req.message());
        if (req.maxOutputTokens() > 0) { requestBody.put("max_tokens", req.maxOutputTokens()); }
//        adapterId.ifPresent(s -> requestBody.put("adapter_id", s));

        URI target = this.endpoint.resolve(apiSpec.get(APIOperation.GENERATE_STREAM));
        HttpRequest httpReq= HttpRequest.newBuilder()
                .uri(target)
                .method("POST", HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .header("Accept-Encoding", "application/json")
                .header("Content-Type", "application/json")
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        try {
            HttpResponse<InputStream> httpResp = httpClient.send(
                    httpReq, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream is = httpResp.body()) {
                int newByte;
                while ((newByte = is.read()) != -1) {
                    response.write(new byte[]{(byte) newByte});
                }
            }

            response.close();
        } catch (Exception e) {
            response.fail(("Inference error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
