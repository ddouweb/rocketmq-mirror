package mirror;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极轻量 Prometheus HTTP exporter,不引入第三方依赖。
 * 监听 /metrics ,输出文本格式指标。
 */
public final class PrometheusExporter {

    private final MirrorMetrics metrics;
    private final int port;
    private final ExecutorService executor;
    private HttpServer server;

    public PrometheusExporter(MirrorMetrics metrics, int port) {
        this.metrics = metrics;
        this.port = port;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics-http");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() throws IOException {
        if (port <= 0) return;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", this::handle);
        server.createContext("/", this::handleRoot);
        server.setExecutor(executor);
        server.start();
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        byte[] body = "mqmirror metrics: see /metrics\n".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = metrics.toPrometheusText().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }
}
