package com.acme.framework.storage.internal;

import com.acme.framework.storage.config.StorageProperties;
import com.acme.framework.storage.support.ObjectKey;
import com.acme.framework.storage.support.StorageObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MinIO 适配器端到端实证（FR-012、AC-012：零 Docker 进程内 S3 HTTP 桩，真实走 MinioClient HTTP
 * 签名与 PUT/HEAD/GET/DELETE 协议栈，不依赖 docker.io 镜像拉取）。
 */
class MinioObjectStorageTest {

    private static final String ACCESS = "acme-test";
    private static final String SECRET = "acme-test-secret";
    private static final String BUCKET = "acme-test";

    private record StoredEntry(byte[] bytes, String contentType) {
    }

    private static HttpServer s3Server;
    private static final Map<String, StoredEntry> OBJECTS = new ConcurrentHashMap<>();
    private static MinioObjectStorage storage;

    @BeforeAll
    static void startInProcessS3AndConnect() throws IOException {
        s3Server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3Server.createContext("/" + BUCKET, MinioObjectStorageTest::handleS3);
        s3Server.start();

        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://127.0.0.1:" + s3Server.getAddress().getPort());
        properties.setAccessKey(ACCESS);
        properties.setSecretKey(SECRET);
        properties.setBucket(BUCKET);
        storage = MinioObjectStorage.connect(properties);
    }

    @AfterAll
    static void stopServer() {
        if (s3Server != null) {
            s3Server.stop(0);
        }
        OBJECTS.clear();
    }

    private static void handleS3(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String prefix = "/" + BUCKET;
            String key = path.length() > prefix.length() ? path.substring(prefix.length() + 1) : "";

            if ("GET".equals(method) && key.isEmpty() && query != null && query.contains("location")) {
                byte[] xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, xml.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(xml);
                }
                return;
            }

            if ("PUT".equals(method) && key.isEmpty()) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            switch (method) {
                case "PUT" -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    OBJECTS.put(key, new StoredEntry(body,
                            contentType != null ? contentType : "application/octet-stream"));
                    exchange.getResponseHeaders().set("ETag", "\"acme-etag-" + body.length + "\"");
                    exchange.sendResponseHeaders(200, -1);
                }
                case "HEAD" -> {
                    StoredEntry entry = OBJECTS.get(key);
                    if (entry == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", entry.contentType());
                        exchange.getResponseHeaders().set("Content-Length", String.valueOf(entry.bytes().length));
                        exchange.getResponseHeaders().set("ETag", "\"acme-etag-" + entry.bytes().length + "\"");
                        exchange.getResponseHeaders().set("Last-Modified",
                                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
                        exchange.sendResponseHeaders(200, -1);
                    }
                }
                case "GET" -> {
                    StoredEntry entry = OBJECTS.get(key);
                    if (entry == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", entry.contentType());
                        exchange.getResponseHeaders().set("ETag", "\"acme-etag-" + entry.bytes().length + "\"");
                        exchange.sendResponseHeaders(200, entry.bytes().length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(entry.bytes());
                        }
                    }
                }
                case "DELETE" -> {
                    OBJECTS.remove(key);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    @Test
    void putGetExistsRemoveRoundTrip() throws Exception {
        ObjectKey key = ObjectKey.of("p5", "hello.txt");
        storage.put(new StorageObject(key,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5, "text/plain"));
        assertThat(storage.exists(key)).isTrue();
        try (InputStream in = storage.get(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(storage.url(key)).isNotBlank();
        storage.remove(key);
        assertThat(storage.exists(key)).isFalse();
    }
}
