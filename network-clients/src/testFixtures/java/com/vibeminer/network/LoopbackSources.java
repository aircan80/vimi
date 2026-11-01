package com.vibeminer.network;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Real transport against an isolated HTTP fixture, while public routes remain official HTTPS
 * addresses.
 */
public final class LoopbackSources implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();
    private final NetworkClients clients;

    public LoopbackSources(NetworkOptions options, Dispatcher dispatcher) throws IOException {
        this(options, dispatcher, false);
    }

    public LoopbackSources(NetworkOptions options, Dispatcher dispatcher, boolean http2)
            throws IOException {
        server.setDispatcher(dispatcher);
        OkHttpClient.Builder transport = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY);
        if (http2) {
            var certificate =
                    new okhttp3.tls.HeldCertificate.Builder()
                            .commonName("localhost")
                            .addSubjectAlternativeName("localhost")
                            .build();
            var serverTls =
                    new okhttp3.tls.HandshakeCertificates.Builder()
                            .heldCertificate(certificate)
                            .build();
            var clientTls =
                    new okhttp3.tls.HandshakeCertificates.Builder()
                            .addTrustedCertificate(certificate.certificate())
                            .build();
            server.useHttps(serverTls.sslSocketFactory(), false);
            transport.sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager());
        }
        server.start();
        java.util.function.Function<URI, URI> target =
                uri ->
                        server.url(
                                        "/"
                                                + uri.getHost()
                                                + uri.getRawPath()
                                                + (uri.getRawQuery() == null
                                                        ? ""
                                                        : "?" + uri.getRawQuery()))
                                .uri();
        OkHttpClient supplied = transport.build();
        clients =
                new NetworkClients(
                        options,
                        new HttpEngine(
                                options, "test-cdn", options.concurrentFetches(), supplied, target),
                        new HttpEngine(options, "test-definitions", 2, supplied, target),
                        new HttpEngine(options, "test-listfile", 1, supplied, target));
    }

    public NetworkClients clients() {
        return clients;
    }

    public RecordedRequest takeRequest() throws InterruptedException {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    public int requestCount() {
        return server.getRequestCount();
    }

    @Override
    public void close() throws IOException {
        try {
            clients.closeAsync().join();
        } finally {
            server.close();
        }
    }
}
