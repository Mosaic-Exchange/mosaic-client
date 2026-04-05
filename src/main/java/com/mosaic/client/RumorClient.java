package com.mosaic.client;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * HTTP client wrapping every exchange-server endpoint.
 *
 * <p>SSE methods ({@link #sendMessage} and {@link #subscribeDownloadProgress}) start a virtual
 * thread and return immediately. Callbacks are invoked on that background thread, so callers that
 * touch JavaFX nodes must wrap updates in {@code Platform.runLater()}.
 */
public class RumorClient {

    private static final String BASE_URL = "http://localhost:8080";

    private final HttpClient http = HttpClient.newHttpClient();

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    public record NodeInfo(String id, String type, String status, List<String> services, boolean self) {}

    public record PeerFile(String nodeId, String name, long size) {}

    public record DownloadProgress(String fileName, long bytesReceived, long totalBytes, String status) {}

    // -------------------------------------------------------------------------
    // GET /api/debug  →  List<NodeInfo>
    // -------------------------------------------------------------------------

    /**
     * Returns the current cluster view as a list of nodes.
     * Each node carries id, type, status, services, and whether it is the local node.
     */
    public List<NodeInfo> getClusterState() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/debug"))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GET /api/debug returned HTTP " + resp.statusCode());
        }
        return parseCluster(resp.body());
    }

    // -------------------------------------------------------------------------
    // POST /api/inference  (SSE stream)
    // DELETE /api/inference (cancel)
    // -------------------------------------------------------------------------

    /**
     * Starts an inference request and streams tokens back via callbacks.
     *
     * <p>SSE event mapping:
     * <ul>
     *   <li>{@code status} — server is processing (WAITING state); no callback.</li>
     *   <li>{@code token}  — calls {@code onToken} with the token text.</li>
     *   <li>{@code done}   — calls {@code onDone}.</li>
     *   <li>{@code error}  — calls {@code onError} with the reason string.</li>
     * </ul>
     *
     * @param prompt   user prompt text
     * @param model    model name, or {@code null} to use server default
     * @param isLocal  {@code true} = run on this node, {@code false} = dispatch to network
     * @param onToken  called for each streamed token
     * @param onDone   called once when inference completes
     * @param onError  called on any error (HTTP or inference failure)
     */
    public void sendMessage(String prompt, String model, boolean isLocal,
                            Consumer<String> onToken, Runnable onDone, Consumer<String> onError) {
        String json = buildInferenceJson(prompt, model, isLocal);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/inference"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        Thread.ofVirtual().start(() -> {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    onError.accept("HTTP " + resp.statusCode());
                    return;
                }
                readSse(resp.body(), (event, data) -> {
                    switch (event) {
                        case "status" -> { /* server is processing — WAITING state, no-op here */ }
                        case "token"  -> onToken.accept(data);
                        case "done"   -> onDone.run();
                        case "error"  -> onError.accept(data);
                    }
                });
            } catch (Exception e) {
                onError.accept(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    /** Cancels the currently active inference request (if any). */
    public void cancelInference() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/inference"))
            .DELETE()
            .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    // -------------------------------------------------------------------------
    // GET /api/models  →  List<PeerFile>
    // -------------------------------------------------------------------------

    /**
     * Discovers all model files available on peer nodes.
     *
     * @return flat list of {@link PeerFile} entries (one per file per peer)
     */
    public List<PeerFile> discoverModels() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/models"))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GET /api/models returned HTTP " + resp.statusCode());
        }
        return parsePeerFiles(resp.body());
    }

    // -------------------------------------------------------------------------
    // POST /api/models/download   (start)
    // DELETE /api/models/download (cancel)
    // -------------------------------------------------------------------------

    /**
     * Requests the server to start downloading {@code fileName} from a peer.
     * Poll progress via {@link #subscribeDownloadProgress}.
     */
    public void startDownload(String fileName) throws IOException, InterruptedException {
        String json = "{\"fileName\":\"" + escJson(fileName) + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/models/download"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("POST /api/models/download returned HTTP "
                + resp.statusCode() + ": " + resp.body());
        }
    }

    /** Cancels the currently active download (if any). */
    public void cancelDownload() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/models/download"))
            .DELETE()
            .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    // -------------------------------------------------------------------------
    // GET /api/models/download/progress  (SSE)
    // -------------------------------------------------------------------------

    /**
     * Subscribes to download progress updates via SSE.
     *
     * <p>SSE event mapping:
     * <ul>
     *   <li>{@code progress}  — calls {@code onProgress}; also triggers {@code onDone} when
     *       status is {@code completed} / {@code cancelled}, or {@code onError} when
     *       {@code failed:*}.</li>
     *   <li>{@code heartbeat} — keep-alive ping, ignored.</li>
     * </ul>
     *
     * @param onProgress called on every progress update
     * @param onDone     called when download completes or is cancelled
     * @param onError    called if the download fails or the HTTP request errors
     */
    public void subscribeDownloadProgress(Consumer<DownloadProgress> onProgress,
                                          Runnable onDone, Consumer<String> onError) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/models/download/progress"))
            .GET()
            .build();

        Thread.ofVirtual().start(() -> {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    onError.accept("HTTP " + resp.statusCode());
                    return;
                }
                readSse(resp.body(), (event, data) -> {
                    switch (event) {
                        case "progress" -> {
                            DownloadProgress dp = parseDownloadProgress(data);
                            if (dp == null) return;
                            onProgress.accept(dp);
                            String status = dp.status();
                            if (status.startsWith("completed") || status.equals("cancelled")) {
                                onDone.run();
                            } else if (status.startsWith("failed")) {
                                onError.accept(status);
                            }
                        }
                        case "heartbeat" -> { /* keep-alive, no-op */ }
                    }
                });
            } catch (Exception e) {
                onError.accept(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    // -------------------------------------------------------------------------
    // SSE reader
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface SseHandler {
        void onEvent(String event, String data);
    }

    /**
     * Reads an SSE stream line by line and dispatches complete events to {@code handler}.
     * Blocks until the stream is closed (server done) or an I/O error occurs.
     */
    private static void readSse(InputStream in, SseHandler handler) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Empty line = end of one event block; dispatch if we have an event type.
                if (currentEvent != null) {
                    handler.onEvent(currentEvent, currentData.toString());
                }
                currentEvent = null;
                currentData.setLength(0);
            } else if (line.startsWith("event: ")) {
                currentEvent = line.substring(7);
            } else if (line.startsWith("data: ")) {
                // Multi-line data fields are joined with \n per SSE spec.
                if (currentData.length() > 0) currentData.append('\n');
                currentData.append(line.substring(6));
            }
            // Ignore comment lines (": ...") and unknown fields.
        }
    }

    // -------------------------------------------------------------------------
    // JSON builders
    // -------------------------------------------------------------------------

    private static String buildInferenceJson(String prompt, String model, boolean isLocal) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"prompt\":\"").append(escJson(prompt)).append("\"");
        if (model != null && !model.isEmpty()) {
            sb.append(",\"model\":\"").append(escJson(model)).append("\"");
        }
        sb.append(",\"local\":").append(isLocal);
        sb.append("}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON parsers
    // -------------------------------------------------------------------------

    /** Parses the {@code cluster} array from a /api/debug response. */
    private static List<NodeInfo> parseCluster(String json) {
        List<NodeInfo> nodes = new ArrayList<>();
        String clusterArr = extractJsonArray(json, "cluster");
        if (clusterArr == null) return nodes;

        for (String obj : splitJsonObjects(clusterArr)) {
            String id       = extractStr(obj, "id");
            String type     = extractStr(obj, "type");
            String status   = extractStr(obj, "status");
            String services = extractStr(obj, "services");
            boolean self    = extractBool(obj, "self", false);

            List<String> serviceList = (services == null || services.isEmpty())
                ? Collections.emptyList()
                : Arrays.asList(services.split(","));

            nodes.add(new NodeInfo(
                id     != null ? id     : "",
                type   != null ? type   : "?",
                status != null ? status : "?",
                serviceList,
                self
            ));
        }
        return nodes;
    }

    /** Parses the {@code peers} array from a /api/models response into a flat file list. */
    private static List<PeerFile> parsePeerFiles(String json) {
        List<PeerFile> files = new ArrayList<>();
        String peersArr = extractJsonArray(json, "peers");
        if (peersArr == null) return files;

        for (String peerObj : splitJsonObjects(peersArr)) {
            String nodeId   = extractStr(peerObj, "nodeId");
            String filesArr = extractJsonArray(peerObj, "files");
            if (filesArr == null) continue;

            for (String fileObj : splitJsonObjects(filesArr)) {
                String name = extractStr(fileObj, "name");
                long   size = extractLong(fileObj, "size", 0);
                if (name != null && !name.isEmpty()) {
                    files.add(new PeerFile(nodeId != null ? nodeId : "", name, size));
                }
            }
        }
        return files;
    }

    /** Parses a single SSE {@code progress} data payload. */
    private static DownloadProgress parseDownloadProgress(String json) {
        // The data field is already the JSON object content (server sends the whole object)
        String fileName    = extractStr(json, "fileName");
        long bytesReceived = extractLong(json, "bytesReceived", 0);
        long totalBytes    = extractLong(json, "totalBytes", 0);
        String status      = extractStr(json, "status");
        if (fileName == null) return null;
        return new DownloadProgress(fileName, bytesReceived, totalBytes, status != null ? status : "");
    }

    // -------------------------------------------------------------------------
    // Minimal JSON extraction helpers — no external library
    // -------------------------------------------------------------------------

    /**
     * Finds {@code "field":[...]} and returns the content between the brackets.
     * Handles nested objects/arrays and quoted strings correctly.
     */
    private static String extractJsonArray(String json, String field) {
        String key = "\"" + field + "\":[";
        int start = json.indexOf(key);
        if (start < 0) return null;
        int bracketPos = start + key.length() - 1; // position of '['
        return extractBalanced(json, bracketPos, '[', ']');
    }

    /**
     * Starting at {@code from} (which must be the {@code open} character), finds the matching
     * {@code close} character and returns the content between them (exclusive of delimiters).
     * Correctly skips over quoted strings and nested balanced pairs.
     */
    private static String extractBalanced(String json, int from, char open, char close) {
        if (from >= json.length() || json.charAt(from) != open) return null;
        int depth = 0;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                // Skip entire quoted string so braces/brackets inside don't confuse us.
                i++;
                while (i < json.length()) {
                    char sc = json.charAt(i);
                    if (sc == '\\') { i++; } // escaped character — skip both chars
                    else if (sc == '"') { break; } // end of string
                    i++;
                }
                continue; // i is now at closing '"'; outer loop will i++ past it
            }
            if (c == open)  depth++;
            if (c == close) {
                depth--;
                if (depth == 0) return json.substring(from + 1, i);
            }
        }
        return null; // unmatched
    }

    /**
     * Splits the body of a JSON array (without outer {@code [ ]}) into individual
     * object content strings (without outer {@code { }}).
     */
    private static List<String> splitJsonObjects(String arrayBody) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < arrayBody.length()) {
            if (arrayBody.charAt(i) == '{') {
                String content = extractBalanced(arrayBody, i, '{', '}');
                if (content != null) {
                    result.add(content);
                    i += content.length() + 2; // +2 for the '{' and '}'
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    /** Extracts a JSON string field value, handling common escape sequences. */
    private static String extractStr(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    default   -> sb.append(next); // handles \" \\ etc.
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extracts a JSON boolean field value. */
    private static boolean extractBool(String json, String field, boolean defaultVal) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) return defaultVal;
        String rest = json.substring(start + key.length()).stripLeading();
        if (rest.startsWith("true"))  return true;
        if (rest.startsWith("false")) return false;
        return defaultVal;
    }

    /** Extracts a JSON numeric (long) field value. */
    private static long extractLong(String json, String field, long defaultVal) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) return defaultVal;
        String rest = json.substring(start + key.length()).stripLeading();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (Character.isDigit(c) || (c == '-' && i == 0)) sb.append(c);
            else break;
        }
        if (sb.isEmpty()) return defaultVal;
        try { return Long.parseLong(sb.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
