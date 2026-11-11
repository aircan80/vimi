package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** Same synthetic scenario drives an independent fake and a real loopback HTTP front end. */
public final class SourceScenario implements AutoCloseable {
    public static final String FIRST = "level3.blizzard.com",
            SECOND = "us.cdn.blizzard.com",
            REVISION = "0123456789abcdef0123456789abcdef01234567";
    public final MemorySources memory;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private final LoopbackSources loopback;
    private final NetworkClients real;

    public SourceScenario(boolean http, NetworkOptions options) throws IOException {
        memory = new MemorySources(options);
        loopback =
                http
                        ? new LoopbackSources(
                                options,
                                new Dispatcher() {
                                    @Override
                                    public MockResponse dispatch(RecordedRequest request) {
                                        return response(request);
                                    }
                                })
                        : null;
        real = loopback == null ? null : loopback.clients();
        release("{}".getBytes(StandardCharsets.UTF_8));
    }

    public CdnClient cdn() {
        return real == null ? memory : real;
    }

    public VersionClient versions() {
        return real == null ? memory : real;
    }

    public ListfileClient listfile() {
        return real == null ? memory : real;
    }

    public void add(ObjectKind kind, CdnKey key, byte[] bytes) {
        memory.add(kind, key, bytes);
        CdnRoute route = new CdnRoute(java.util.List.of(FIRST, SECOND), "tpr/wow");
        for (String host : route.hosts()) {
            put(route.urlFor(host, kind, key), bytes);
        }
    }

    public void fail(String host, Failure failure) {
        memory.fail(host, failure);
        if (failure == null) {
            failures.remove(host);
        } else {
            failures.put(host, failure);
        }
    }

    public void version(String product, String region, DocumentKind kind, byte[] bytes) {
        memory.version(product, region, kind, bytes);
        put(SourceUrls.version(product, region, kind), bytes);
    }

    public void release(byte[] bytes) {
        memory.release(bytes);
        put(SourceUrls.LATEST_RELEASE, bytes);
    }

    public void asset(String tag, byte[] bytes) {
        memory.asset(tag, bytes);
        put(SourceUrls.asset(tag), bytes);
    }

    private void put(URI uri, byte[] bytes) {
        objects.put(
                "/"
                        + uri.getHost()
                        + uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()),
                bytes.clone());
    }

    private MockResponse response(RecordedRequest request) {
        String path = request.getPath();
        String host = path.split("/", 3)[1];
        Failure fail = failures.get(host);
        if (fail == Failure.NOT_FOUND) {
            return new MockResponse().setResponseCode(404);
        }
        if (fail == Failure.HTTP_STATUS) {
            return new MockResponse().setResponseCode(503);
        }
        byte[] bytes = objects.get(path);
        if (bytes == null) {
            return new MockResponse().setResponseCode(404);
        }
        if (fail == Failure.VERIFICATION_FAILED) {
            bytes = bytes.clone();
            if (bytes.length > 0) {
                bytes[0] ^= 1;
            }
        }
        String range = request.getHeader("Range");
        if (range != null && fail != Failure.RANGE_IGNORED) {
            String[] values = range.substring(6).split("-");
            long start = Long.parseLong(values[0]),
                    end = Math.min(bytes.length - 1, Long.parseLong(values[1]));
            if (start >= bytes.length) {
                return new MockResponse().setResponseCode(416);
            }
            return new MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Type", "application/octet-stream")
                    .addHeader("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length)
                    .setBody(
                            new Buffer()
                                    .write(Arrays.copyOfRange(bytes, (int) start, (int) end + 1)));
        }
        return new MockResponse()
                .setResponseCode(200)
                .addHeader(
                        "Content-Type",
                        path.contains("version.battle.net")
                                ? "text/plain"
                                : "application/octet-stream")
                .setBody(new Buffer().write(bytes));
    }

    @Override
    public void close() throws IOException {
        if (loopback != null) {
            loopback.close();
        }
    }
}
