package com.vibeminer.network;

import com.vibeminer.network.NetworkTypes.Attempt;
import com.vibeminer.network.NetworkTypes.Failure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Serial;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Internal transport: no redirects, retries, gzip, cookies, persistence or unbounded response
 * buffering.
 */
final class HttpEngine {
    static final ScheduledThreadPoolExecutor TIMERS =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("network-deadline-", 0).factory());

    static {
        TIMERS.setRemoveOnCancelPolicy(true);
    }

    final NetworkOptions options;
    final NetworkQueue queue;
    private final OkHttpClient client;
    private final Function<URI, URI> wireUri;

    HttpEngine(NetworkOptions options, String name, int concurrent) {
        this(options, name, concurrent, null, Function.identity());
    }

    // Per-instance loopback seam, inaccessible to application consumers; no global test switches.
    HttpEngine(
            NetworkOptions options,
            String name,
            int concurrent,
            OkHttpClient supplied,
            Function<URI, URI> wireUri) {
        this.options = options;
        this.wireUri = wireUri;
        OkHttpClient.Builder builder =
                supplied == null ? new OkHttpClient.Builder() : supplied.newBuilder();
        client =
                builder.followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .connectTimeout(
                                options.timeouts().connect().toMillis(), TimeUnit.MILLISECONDS)
                        .connectionPool(new ConnectionPool(concurrent, 5, TimeUnit.MINUTES))
                        // OkHttp otherwise follows a 503 with Retry-After: 0 even when connection
                        // retries are disabled.
                        .addNetworkInterceptor(
                                chain -> {
                                    java.util.concurrent.atomic.AtomicInteger exchanges =
                                            chain.request()
                                                    .tag(
                                                            java.util.concurrent.atomic
                                                                    .AtomicInteger.class);
                                    if (exchanges != null && exchanges.incrementAndGet() > 1) {
                                        throw new java.net.ProtocolException(
                                                "Automatic follow-up request refused");
                                    }
                                    Response response = chain.proceed(chain.request());
                                    return response.code() == 503
                                            ? response.newBuilder()
                                                    .removeHeader("Retry-After")
                                                    .build()
                                            : response;
                                })
                        .build();
        queue =
                new NetworkQueue(
                        name,
                        concurrent,
                        options.queuedFetches(),
                        () -> {
                            client.connectionPool().evictAll();
                            client.dispatcher().executorService().shutdown();
                        });
    }

    static final class Fault extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final Failure failure;
        final Integer status;

        Fault(Failure failure, String message, Integer status) {
            super(message);
            this.failure = failure;
            this.status = status;
        }
    }

    static Fault fault(Failure failure, String message, Integer status) {
        return new Fault(failure, message, status);
    }

    static final class Budget {
        private final long start = System.nanoTime(), nanos;

        Budget(Duration duration) {
            nanos = duration == null ? Long.MAX_VALUE : duration.toNanos();
        }

        long remaining() {
            long left =
                    nanos == Long.MAX_VALUE ? Long.MAX_VALUE : nanos - (System.nanoTime() - start);
            if (left <= 0) {
                throw fault(Failure.TIMEOUT, "Fetch exceeded its total time budget", null);
            }
            return left;
        }
    }

    CdnException failure(URI uri, Fault fault) {
        return new CdnException(List.of(attempt(uri, fault, null)));
    }

    Attempt attempt(URI uri, Fault fault, String code) {
        URI safe = safe(uri);
        return new Attempt(
                safe.getHost(),
                safe,
                fault.failure,
                fault.status,
                options.clock().instant(),
                fault.getMessage(),
                code);
    }

    static URI safe(URI uri) {
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath());
    }

    Exchange send(
            URI uri,
            Map<String, String> headers,
            Cancellation token,
            Budget budget,
            boolean external) {
        token.check();
        long remaining = budget.remaining();
        URI target = wireUri.apply(uri);
        Request.Builder request =
                new Request.Builder()
                        .url(target.toString())
                        .header("User-Agent", options.userAgent())
                        .header("Accept-Encoding", "identity")
                        .tag(
                                java.util.concurrent.atomic.AtomicInteger.class,
                                new java.util.concurrent.atomic.AtomicInteger());
        headers.forEach(request::header);
        Duration headerTime =
                external ? options.timeouts().externalHeaders() : options.timeouts().headers();
        OkHttpClient transport =
                client.newBuilder()
                        .readTimeout(headerTime.toMillis(), TimeUnit.MILLISECONDS)
                        .build();
        Call call = transport.newCall(request.build());
        if (remaining != Long.MAX_VALUE) {
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
        }
        AtomicBoolean waiting = new AtomicBoolean(true), expired = new AtomicBoolean();
        ScheduledFuture<?> timer =
                TIMERS.schedule(
                        () -> {
                            if (waiting.compareAndSet(true, false)) {
                                expired.set(true);
                                call.cancel();
                            }
                        },
                        headerTime.toNanos(),
                        TimeUnit.NANOSECONDS);
        Cancellation.Registration registration = token.onCancel(call::cancel);
        Response response = null;
        boolean handedOff = false;
        try {
            response = call.execute();
            if (!waiting.compareAndSet(true, false)) {
                call.cancel();
                response.close();
                token.check();
                throw fault(Failure.TIMEOUT, "No headers within the header budget", null);
            }
            timer.cancel(false);
            token.check();
            budget.remaining();
            if (!response.request().url().uri().equals(target)) {
                call.cancel();
                response.close();
                throw fault(
                        Failure.PROTOCOL,
                        "Automatic redirect or address change refused",
                        response.code());
            }
            if (response.body() == null) {
                call.cancel();
                response.close();
                throw fault(Failure.NETWORK, "Response has no body", response.code());
            }
            response.body()
                    .source()
                    .timeout()
                    .timeout(options.timeouts().idle().toNanos(), TimeUnit.NANOSECONDS);
            handedOff = true;
            return new Exchange(
                    response, call, registration, token, budget, options.clock().instant());
        } catch (IOException e) {
            token.check();
            budget.remaining();
            if (expired.get() || e instanceof InterruptedIOException) {
                throw fault(Failure.TIMEOUT, "Network response timed out", null);
            }
            boolean offline =
                    e instanceof UnknownHostException
                            || e instanceof ConnectException
                            || e instanceof SocketException
                            || !external && e instanceof SSLException
                            || external && e instanceof java.io.EOFException;
            throw fault(
                    offline ? Failure.OFFLINE : Failure.NETWORK,
                    offline ? "Host could not be reached" : "Network response failed",
                    null);
        } finally {
            waiting.set(false);
            timer.cancel(false);
            if (!handedOff) {
                call.cancel();
                if (response != null) {
                    response.close();
                }
                registration.close();
            }
        }
    }

    final class Exchange implements AutoCloseable {
        final Response response;
        final Instant observedAt;
        private final Call call;
        private final Cancellation.Registration registration;
        private final Cancellation token;
        private final Budget budget;
        private boolean eof;

        Exchange(
                Response response,
                Call call,
                Cancellation.Registration registration,
                Cancellation token,
                Budget budget,
                Instant observedAt) {
            this.response = response;
            this.call = call;
            this.registration = registration;
            this.token = token;
            this.budget = budget;
            this.observedAt = observedAt;
        }

        String header(String name) {
            return response.header(name);
        }

        Long length() {
            List<String> values = response.headers("Content-Length");
            if (values.isEmpty()) {
                return null;
            }
            if (values.size() != 1 || !values.getFirst().matches("[0-9]{1,19}")) {
                throw fault(Failure.PROTOCOL, "Invalid Content-Length", response.code());
            }
            try {
                return Long.parseLong(values.getFirst());
            } catch (NumberFormatException e) {
                throw fault(Failure.PROTOCOL, "Invalid Content-Length", response.code());
            }
        }

        int read(byte[] buffer, int length) {
            token.check();
            budget.remaining();
            try {
                int n = response.body().byteStream().read(buffer, 0, length);
                if (n < 0) {
                    eof = true;
                }
                return n;
            } catch (IOException e) {
                token.check();
                budget.remaining();
                throw fault(
                        e instanceof InterruptedIOException ? Failure.TIMEOUT : Failure.NETWORK,
                        e instanceof InterruptedIOException
                                ? "Response body stalled"
                                : "Response body was interrupted",
                        response.code());
            }
        }

        byte[] bytes(int maximum, Failure oversized, boolean emptyAllowed) {
            Long declared = length();
            if (declared != null && declared > maximum) {
                throw fault(oversized, "Response exceeds its byte bound", response.code());
            }
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(
                            (int) Math.min(maximum, declared == null ? 65536 : declared));
            byte[] buffer = new byte[Math.min(65536, maximum + 1)];
            while (true) {
                int n = read(buffer, Math.min(buffer.length, maximum - bytes.size() + 1));
                if (n < 0) {
                    break;
                }
                if (bytes.size() + n > maximum) {
                    throw fault(oversized, "Response exceeds its byte bound", response.code());
                }
                bytes.write(buffer, 0, n);
            }
            if (declared != null && declared != bytes.size()) {
                throw fault(
                        bytes.size() < declared ? Failure.NETWORK : Failure.SIZE_CONFLICT,
                        "Response length differs from its declaration",
                        response.code());
            }
            if (bytes.size() == 0 && !emptyAllowed) {
                throw fault(Failure.SIZE_CONFLICT, "Response is empty", response.code());
            }
            return bytes.toByteArray();
        }

        @Override
        public void close() {
            if (!eof) {
                call.cancel();
            }
            try {
                response.close();
            } finally {
                registration.close();
            }
        }
    }
}
