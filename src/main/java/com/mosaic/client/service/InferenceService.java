package com.mosaic.client.service;

import org.rumor.service.DistributedService;
import org.rumor.service.ServiceRequest;
import org.rumor.service.ServiceResponse;
import org.rumor.service.Streamable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

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

    private final String endpoint;
    private final String defaultModel;
    private final HttpClient httpClient;

    public InferenceService() {
        this("http://localhost:11434", "llama3.2");
    }

    public InferenceService(String endpoint, String defaultModel) {
        this.endpoint = endpoint;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void serve(ServiceRequest<InferenceRequest> request, ServiceResponse<byte[]> response) {
        InferenceRequest req = request.data();
        String model = req.model() != null ? req.model() : defaultModel;

        String json = buildRequestJson(model, req);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<Stream<String>> httpResp = httpClient.send(
                    httpReq, HttpResponse.BodyHandlers.ofLines());

            httpResp.body().forEach(line -> {
                if (line.isBlank()) return;

                String token = extractField(line, "response");
                if (token != null && !token.isEmpty()) {
                    response.write(token.getBytes(StandardCharsets.UTF_8));
                }
            });

            response.close();
        } catch (Exception e) {
            response.fail(("Inference error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildRequestJson(String model, InferenceRequest req) {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(escapeJson(model))
            .append("\",\"prompt\":\"").append(escapeJson(req.prompt()))
            .append("\",\"stream\":true");

        if (req.maxOutputTokens() > 0) {
            json.append(",\"options\":{\"num_predict\":").append(req.maxOutputTokens());
            if (req.temperature() > 0) {
                json.append(",\"temperature\":").append(req.temperature());
            }
            json.append("}");
        } else if (req.temperature() > 0) {
            json.append(",\"options\":{\"temperature\":").append(req.temperature()).append("}");
        }

        json.append("}");
        return json.toString();
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case 'r'  -> sb.append('\r');
                    default   -> { sb.append('\\'); sb.append(next); }
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
