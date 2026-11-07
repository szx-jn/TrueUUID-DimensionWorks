package cn.alini.trueuuid.server;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Authentication-only transport; works without launcher or panel JVM flags. */
final class SessionHttpClient {
    private static final HttpClient HTTP = newClientBuilder().build();

    static HttpClient.Builder newClientBuilder() {
        // TLS 1.2 succeeded where the default Java 17 HTTPS connection failed
        // with EOF/timeouts on the deployed server.
        // Configure this client only; keep the default trust store and HTTPS
        // hostname checks, without changing any JVM-wide network properties.
        SSLParameters tls = new SSLParameters();
        tls.setProtocols(new String[]{"TLSv1.2"});
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslParameters(tls);
    }

    static CompletableFuture<HttpResponse<String>> get(URI uri) {
        return get(HTTP, uri);
    }

    static CompletableFuture<HttpResponse<String>> get(HttpClient client, URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .GET().build();
        return send(client, request, true);
    }

    private static CompletableFuture<HttpResponse<String>> send(
            HttpClient client, HttpRequest request, boolean retry) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    Throwable cause = unwrap(failure);
                    // These authentication requests are idempotent GETs. Retry one interrupted
                    // connection, never HTTP authentication failures or TLS
                    // certificate errors. Both attempts finish within 16 s.
                    if (retry && cause instanceof IOException && !(cause instanceof SSLException)) {
                        return send(client, request, false);
                    }
                    return CompletableFuture.<HttpResponse<String>>failedFuture(cause);
                }).thenCompose(result -> result);
    }

    static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private SessionHttpClient() {}
}
