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
import java.util.function.Supplier;

/**
 * Streams adapter GGUF files between peers and publishes two gossip keys:
 *
 * <ul>
 *   <li><b>{@code ADAPTERS}</b> — comma-separated {@code name:size} for every
 *       {@code .gguf} file in the shared adapters directory (available for download).</li>
 *   <li><b>{@code CATALOG}</b> — comma-separated
 *       {@code ggufFile|displayName|domain|serverSideId} for adapters that are
 *       currently loaded on this node (available for remote inference).</li>
 * </ul>
 *
 * <p><b>Request format</b> (UTF-8): adapter filename.
 * <p><b>Response</b>: raw file bytes, streamed in 64 KB chunks.
 */
@Streamable
@MaintainState
public class AdapterTransferService extends DistributedService {

    public static final String STATE_KEY   = "ADAPTERS";
    public static final String CATALOG_KEY = "CATALOG";
    private static final int READ_BUFFER_SIZE = 64 * 1024;

    private final Path adaptersRoot;
    private final Supplier<String> catalogSupplier;

    /**
     * @param adaptersRoot    flat directory of {@code .gguf} files to serve and advertise
     * @param catalogSupplier builds the CATALOG state string on demand;
     *                        format: {@code "file.gguf|name|domain|serverId,..."}
     */
    public AdapterTransferService(Path adaptersRoot, Supplier<String> catalogSupplier) {
        this.adaptersRoot    = adaptersRoot.toAbsolutePath().normalize();
        this.catalogSupplier = catalogSupplier;
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

    @StateKey("CATALOG")
    public String computeCatalog() {
        return catalogSupplier.get();
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
     * Returns remote peers' adapter catalog from gossip state.
     *
     * @return map of node ID to catalog string (comma-separated ggufFile|name|domain|serverId)
     */
    public Map<NodeId, String> discoverAdapterCatalog() {
        return clusterView().stateForKey(qualifiedKey(CATALOG_KEY));
    }

    /**
     * Downloads an adapter from a peer that has it.
     *
     * @param adapterName   the adapter filename to download (e.g. {@code "myAdapter.gguf"})
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
