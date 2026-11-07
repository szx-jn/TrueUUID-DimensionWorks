package cn.alini.trueuuid.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SessionHttpClientTest {
    @TempDir static Path certificates;
    private static SSLContext serverTls;
    private static SSLContext trustedTls;
    private HttpsServer server;
    private ExecutorService handlers;
    private HttpClient client;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> protocol = new AtomicReference<>();

    @BeforeAll
    static void createTemporaryCertificate() throws Exception {
        Path store = certificates.resolve("localhost.p12");
        String password = "temporary-test-certificate";
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "localhost", "-keyalg", "EC",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", password, "-keypass", password,
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "2", "-noprompt")
                .redirectErrorStream(true).start();
        assertTrue(keytool.waitFor(10, TimeUnit.SECONDS), "keytool did not finish");
        assertEquals(0, keytool.exitValue(), new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, password.toCharArray());
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password.toCharArray());
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);
        trustedTls = SSLContext.getInstance("TLS");
        trustedTls.init(null, trustManagers.getTrustManagers(), null);
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls) {
            @Override public void configure(HttpsParameters parameters) {
                var tls = serverTls.getDefaultSSLParameters();
                tls.setProtocols(new String[]{"TLSv1.2"});
                parameters.setSSLParameters(tls);
            }
        });
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        client = SessionHttpClient.newClientBuilder().sslContext(trustedTls).build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void retriesInterruptedResponseOnceAndUsesTls12() throws Exception {
        server.createContext("/hasJoined", exchange -> {
            protocol.set(((HttpsExchange) exchange).getSSLSession().getProtocol());
            if (requests.incrementAndGet() == 1) {
                interruptResponse(exchange);
            } else {
                reply(exchange, "verified-session");
            }
        });
        server.start();
        var response = SessionHttpClient.get(client, endpoint("localhost")).get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("verified-session", response.body());
        assertEquals(2, requests.get());
        assertEquals("TLSv1.2", protocol.get());
    }

    @Test
    void invalidSession204IsReturnedWithoutRetry() throws Exception {
        server.createContext("/hasJoined", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var response = SessionHttpClient.get(client, endpoint("localhost")).get(5, TimeUnit.SECONDS);
        assertEquals(204, response.statusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void repeatedConnectionFailureStaysExceptionalAfterOneRetry() throws Exception {
        server.createContext("/hasJoined", exchange -> {
            requests.incrementAndGet();
            interruptResponse(exchange);
        });
        server.start();
        var failure = assertThrows(ExecutionException.class,
                () -> SessionHttpClient.get(client, endpoint("localhost")).get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(2, requests.get());
    }

    @Test
    void stalledAuthenticationRequestTimesOutIncludingRetry() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/hasJoined", exchange -> {
            requests.incrementAndGet();
            try {
                release.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var failure = assertThrows(ExecutionException.class,
                    () -> SessionHttpClient.get(client, endpoint("localhost")).get(22, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            assertEquals(2, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void defaultTrustStoreRejectsUntrustedCertificate() {
        server.createContext("/hasJoined", exchange -> reply(exchange, "untrusted"));
        server.start();
        var failure = assertThrows(ExecutionException.class,
                () -> SessionHttpClient.get(endpoint("localhost")).get(5, TimeUnit.SECONDS));
        assertInstanceOf(SSLException.class, failure.getCause());
    }

    @Test
    void trustedCertificateStillRequiresMatchingHostname() {
        server.createContext("/hasJoined", exchange -> reply(exchange, "wrong-hostname"));
        server.start();
        var failure = assertThrows(ExecutionException.class,
                () -> SessionHttpClient.get(client, endpoint("127.0.0.1")).get(5, TimeUnit.SECONDS));
        assertInstanceOf(SSLException.class, failure.getCause());
    }

    private URI endpoint(String hostname) {
        return URI.create("https://" + hostname + ":" + server.getAddress().getPort() + "/hasJoined");
    }

    private static void interruptResponse(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 100);
        exchange.getResponseBody().write("partial".getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
