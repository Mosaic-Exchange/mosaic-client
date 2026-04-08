package com.mosaic.client.service;

import org.rumor.gossip.NodeId;
import org.rumor.service.DistributedService;
import org.rumor.service.MaintainState;
import org.rumor.service.OnStateChange;
import org.rumor.service.ServiceHandle;
import org.rumor.service.ServiceRequest;
import org.rumor.service.ServiceResponse;
import org.rumor.service.StateKey;
import org.rumor.service.Streamable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Predicate;

/**
 * Streams adapter files between peers and publishes available adapters
 * into gossip state for discovery.
 *
 * <p><b>State published</b> (key {@code ADAPTERS}):
 * comma-separated entries of {@code name:size} for every adapter file in
 * the adapters directory.
 *
 * <p><b>Request format</b> (UTF-8): adapter filename.
 * <p><b>Response</b>: raw file bytes, streamed in chunks.
 */
@Streamable
@MaintainState
public class AdapterTransferService extends DistributedService {

    public static final String STATE_KEY = "ADAPTERS";
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final Path adaptersRoot;

    public AdapterTransferService(Path adaptersRoot) {
        this.adaptersRoot = adaptersRoot.toAbsolutePath().normalize();
    }

    // -- State publishing --

    @StateKey("ADAPTERS")
    public String computeState() {
        if (!Files.isDirectory(adaptersRoot)) return "";

        StringJoiner joiner = new StringJoiner(",");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(adaptersRoot)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String name = adaptersRoot.relativize(entry).toString();
                    long size = Files.size(entry);
                    joiner.add(name + ":" + size);
                }
            }
        } catch (IOException e) {
            return "";
        }
        return joiner.toString();
    }

    // -- Client-side helpers --

    /**
     * Returns remote peers' adapter listings from gossip state.
     *
     * @return map of node ID to adapter listing string (comma-separated name:size)
     */
    public Map<NodeId, String> discoverAdapters() {
        return clusterView().stateForKey(qualifiedKey(STATE_KEY));
    }

    /**
     * Downloads an adapter from a peer that has it.
     *
     * @param adapterName   the adapter filename to download
     * @param onStateChange callback for request lifecycle events
     * @return a handle that can be used to cancel the download
     */
    @SuppressWarnings("unchecked")
    public ServiceHandle downloadAdapter(String adapterName, OnStateChange onStateChange) {
        byte[] request = adapterName.getBytes(StandardCharsets.UTF_8);
        Predicate<Map<String, String>> filter = appState -> {
            String adapters = appState.get(qualifiedKey(STATE_KEY));
            if (adapters == null || adapters.isEmpty()) return false;
            for (String entry : adapters.split(",")) {
                String name = entry.contains(":")
                        ? entry.substring(0, entry.lastIndexOf(':'))
                        : entry;
                if (name.equals(adapterName)) return true;
            }
            return false;
        };
        return dispatch(request, onStateChange, filter);
    }

    // -- Server-side: stream adapter bytes --

    @Override
    public void serve(ServiceRequest request, ServiceResponse response) {
        String fileName = new String(request.raw(), StandardCharsets.UTF_8).trim();
        Path filePath = adaptersRoot.resolve(fileName).normalize();

        // Path traversal guard
        if (!filePath.startsWith(adaptersRoot)) {
            response.fail("path outside adapters root".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (!Files.isRegularFile(filePath)) {
            response.fail("adapter not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        try (InputStream in = Files.newInputStream(filePath)) {
            byte[] buf = new byte[READ_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buf)) != -1) {
                if (bytesRead == buf.length) {
                    response.write(buf);
                } else {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buf, 0, chunk, 0, bytesRead);
                    response.write(chunk);
                }
            }
            response.close();
        } catch (IOException e) {
            response.fail(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }
}
