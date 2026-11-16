# Lab 7 — Toàn bộ mã nguồn hoàn chỉnh

Bản tổng hợp này phản ánh giao diện **Enterprise Light Mode** mới nhất:
header nền trắng, chữ xanh đậm, icon Ikonli FontAwesome 5, 4 thẻ thống kê,
bảng Request có icon ở tiêu đề cột, thanh URL có icon Search, nút **SEND**
với icon Paper Plane và dải badge `Time | IP | Port | Status` có icon mạng.

## Thư viện cần có trong `libs/`

```text
javafx-sdk-21.0.5/
gson-2.11.0.jar
mysql-connector-j-9.4.0.jar
slf4j-api-1.7.36.jar
slf4j-nop-1.7.36.jar
sqlite-jdbc-3.50.3.0.jar
ikonli-core-12.3.1.jar
ikonli-javafx-12.3.1.jar
ikonli-fontawesome5-pack-12.3.1.jar
```

## Chạy nhanh

```bat
run.bat
```

> Proxy JSON được triển khai trong `NetworkServer.handleProxy()` (tương đương
> `ProxyServer` độc lập), trả về `status`, `statusCode`, `statusText`, `ip`,
> `port`, `time` và `html`.

## `src/App.java`

_Khởi động ứng dụng JavaFX, thiết lập UTF-8_

```java
import javafx.application.Application;
import javafx.stage.Stage;
import ui.MainView;

/**
 * Entry point JavaFX của Lab 7.
 *
 * <p>Chạy: {@code java --module-path libs/javafx-sdk-21.0.5/lib
 * --add-modules javafx.controls,javafx.web -cp "out;libs/*" App}
 */
public final class App extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        System.setProperty("file.encoding", "UTF-8");
        new MainView().show(stage);
    }
}
```

## `src/core/NetworkServer.java`

_HTTP server raw socket + proxy JSON trong handleProxy()_

```java
package core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web server HTTP/1.1 thuần socket + toàn bộ logic proxy của Lab 7.
 *
 * <p>Một tiến trình duy nhất phục vụ:
 * <ul>
 *   <li>{@code GET /} — giao diện web client {@code web/index.html}</li>
 *   <li>{@code GET /proxy?url=...} — tải URL bằng {@link NetworkClient}, trả JSON
 *       kèm {@code time / ip / port / status / html} cho giao diện web</li>
 *   <li>{@code GET /history} — lịch sử duyệt web từ MySQL</li>
 *   <li>{@code GET /__health} — trạng thái server + database</li>
 *   <li>{@code GET|HEAD|POST /...} — các trang động lưu trong bảng {@code web_pages}</li>
 * </ul>
 *
 * <p>Mọi request/response đều được đọc/ghi trực tiếp trên {@link Socket}. Không dùng
 * {@code com.sun.net.httpserver} hay bất kỳ web framework nào.
 */
public final class NetworkServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8080;
    public static final String SERVER_NAME = "Lab7-RawSocket/2.0";

    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    /** Thông tin một request đã xử lý, dùng cho bảng dashboard. */
    public record RequestInfo(LocalTime time, String method, String path, int status,
            long millis, String clientIp, int clientPort, long bytes, String note) {

        public String timeText() {
            return time == null ? "" : time.format(TIME_FORMAT);
        }

        public String clientText() {
            return clientIp + ":" + clientPort;
        }
    }

    /** Callback để UI cập nhật log và bảng request theo thời gian thực. */
    public interface Listener {
        void onLog(String message);

        default void onRequest(RequestInfo info) {
        }
    }

    private record HttpRequest(String method, String rawTarget, String path, String query,
            String version, Map<String, String> headers, byte[] body, String clientIp,
            int clientPort) {

        String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private record HttpResponse(int status, String reason, String contentType,
            byte[] body, Map<String, String> extraHeaders) {

        static HttpResponse html(int status, String body) {
            return new HttpResponse(status, reasonOf(status), "text/html; charset=UTF-8",
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        static HttpResponse json(int status, Object value) {
            return new HttpResponse(status, reasonOf(status), "application/json; charset=UTF-8",
                    GSON.toJson(value).getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    private final DatabaseManager database;
    private final Listener listener;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();

    private volatile boolean running;
    private volatile int port = DEFAULT_PORT;
    private volatile ServerSocket serverSocket;
    private volatile ExecutorService executor;
    private volatile Thread acceptThread;
    private volatile Instant startedAt;

    public NetworkServer(DatabaseManager database, Listener listener) {
        this.database = database;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // Vòng đời server
    // ------------------------------------------------------------------

    public synchronized void start(int requestedPort) throws IOException {
        if (running) {
            throw new IOException("Server đang chạy ở cổng " + port + ".");
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(requestedPort));
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Đang xử lý lỗi bind.
            }
            throw new IOException("Không mở được cổng " + requestedPort + ": " + e.getMessage(), e);
        }

        serverSocket = socket;
        port = socket.getLocalPort();
        startedAt = Instant.now();
        requestCount.set(0);
        activeConnections.set(0);
        running = true;

        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "lab7-http-worker");
            thread.setDaemon(true);
            return thread;
        });
        acceptThread = new Thread(this::acceptLoop, "lab7-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log("Server đã bật tại " + baseUrl());
        log("Web client: " + baseUrl() + "/  ·  MySQL: " + database.endpoint());
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Đang dừng server.
            }
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log("Server đã dừng.");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public long requestCount() {
        return requestCount.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long uptimeSeconds() {
        Instant start = startedAt;
        return start == null ? 0 : Math.max(0, Duration.between(start, Instant.now()).toSeconds());
    }

    public String uptimeText() {
        long seconds = uptimeSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    // ------------------------------------------------------------------
    // Accept loop
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                activeConnections.incrementAndGet();
                ExecutorService pool = executor;
                if (pool == null || !running) {
                    closeQuietly(client);
                    activeConnections.decrementAndGet();
                    break;
                }
                try {
                    pool.execute(() -> handleClient(client));
                } catch (RejectedExecutionException e) {
                    closeQuietly(client);
                    activeConnections.decrementAndGet();
                }
            } catch (SocketException e) {
                if (running) {
                    log("Lỗi accept socket: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    log("Lỗi accept: " + e.getMessage());
                }
            }
        }
    }

    private static String toIpv4(java.net.InetAddress address) {
        if (address == null) {
            return "";
        }
        if (address instanceof java.net.Inet4Address) {
            return address.getHostAddress();
        }
        if (address.isLoopbackAddress()) {
            return "127.0.0.1";
        }
        String hostName = address.getCanonicalHostName();
        if (hostName != null && !hostName.equals(address.getHostAddress())) {
            try {
                for (java.net.InetAddress candidate : java.net.InetAddress.getAllByName(hostName)) {
                    if (candidate instanceof java.net.Inet4Address) {
                        return candidate.getHostAddress();
                    }
                }
            } catch (java.net.UnknownHostException ignored) {
            }
        }
        return address.getHostAddress();
    }

    private static String toIpv4(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip == null ? "" : ip;
        }
        String value = ip.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains(".") && !value.contains(":")) {
            return value;
        }
        if ("0:0:0:0:0:0:0:1".equals(value) || "::1".equals(value)) {
            return "127.0.0.1";
        }
        try {
            for (java.net.InetAddress candidate : java.net.InetAddress.getAllByName(value)) {
                if (candidate instanceof java.net.Inet4Address) {
                    return candidate.getHostAddress();
                }
            }
        } catch (java.net.UnknownHostException ignored) {
        }
        return value;
    }

    private void handleClient(Socket socket) {
        long started = System.nanoTime();
        String clientIp = "?";
        int clientPort = 0;
        String method = "?";
        String path = "?";
        int status = 500;
        long bytes = 0;
        String note = "";
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            clientIp = toIpv4(socket.getInetAddress());
            clientPort = socket.getPort();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            HttpRequest request;
            try {
                request = readRequest(in, clientIp, clientPort);
            } catch (HttpProtocolException e) {
                HttpResponse response = HttpResponse.json(e.status, errorBody(e.getMessage()));
                sendResponse(out, "GET", response);
                log(clientIp + ":" + clientPort + " → " + e.status + " · " + e.getMessage());
                return;
            }

            method = request.method();
            path = request.path();
            HttpResponse response;
            try {
                response = route(request);
            } catch (Exception e) {
                response = HttpResponse.json(500, errorBody("Lỗi server: " + safeMessage(e)));
                log("Lỗi xử lý " + method + " " + path + ": " + safeMessage(e));
            }
            status = response.status;
            note = request.header("X-Lab7-Note") == null ? "" : request.header("X-Lab7-Note");
            bytes = sendResponse(out, method, response);
            if (response.extraHeaders != null) {
                note = response.extraHeaders.getOrDefault("X-Lab7-Note", note);
            }
        } catch (SocketException e) {
            note = "Kết nối bị đóng";
        } catch (IOException e) {
            status = 500;
            note = safeMessage(e);
        } finally {
            activeConnections.decrementAndGet();
            requestCount.incrementAndGet();
            long millis = (System.nanoTime() - started) / 1_000_000;
            RequestInfo info = new RequestInfo(LocalTime.now(), method, path, status,
                    millis, clientIp, clientPort, bytes, note);
            if (listener != null) {
                try {
                    listener.onRequest(info);
                } catch (RuntimeException ignored) {
                    // UI không được làm chết server.
                }
            }
            log(String.format("%s %s %s → %d (%d ms, %d B)%s",
                    clientIp + ":" + clientPort, method, path, status, millis, bytes,
                    note.isBlank() ? "" : " · " + note));
        }
    }

    // ------------------------------------------------------------------
    // Router
    // ------------------------------------------------------------------

    private HttpResponse route(HttpRequest request) throws Exception {
        String path = request.path();
        String method = request.method();

        if ("/favicon.ico".equals(path)) {
            return new HttpResponse(204, "No Content", "image/x-icon", new byte[0], Map.of());
        }

        if ("/".equals(path) || "/index.html".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return serveWebClient();
        }

        if ("/proxy".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return handleProxy(request);
        }

        if ("/history".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return handleHistory();
        }

        if ("/__health".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return handleHealth();
        }

        if ("/web_data.json".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return new HttpResponse(200, "OK", "application/json; charset=UTF-8",
                    database.readPagesJson().getBytes(StandardCharsets.UTF_8), Map.of());
        }

        if ("/web_history.json".equals(path)) {
            if (!isReadable(method)) {
                return methodNotAllowed(method);
            }
            return new HttpResponse(200, "OK", "application/json; charset=UTF-8",
                    database.readHistoryJson().getBytes(StandardCharsets.UTF_8), Map.of());
        }

        if (!isReadable(method) && !"POST".equals(method)) {
            return methodNotAllowed(method);
        }

        DatabaseManager.Page page;
        try {
            page = database.findByPath(path);
        } catch (DatabaseManager.DatabaseException e) {
            return HttpResponse.json(503, errorBody(e.getMessage()));
        }
        if (page == null) {
            return HttpResponse.html(404, errorPage(404, "Không tìm thấy trang",
                    "Đường dẫn <code>" + escapeHtml(path) + "</code> chưa có trong bảng "
                            + "<code>web_pages</code>."));
        }
        if ("POST".equals(method)) {
            if ("/lien-he".equals(path)) {
                return postContact(request, page);
            }
            return HttpResponse.html(200, page.content());
        }
        return new HttpResponse(200, "OK", page.contentType(),
                page.content().getBytes(StandardCharsets.UTF_8), Map.of("X-Lab7-Note", page.title()));
    }

    private HttpResponse serveWebClient() {
        Path file = Path.of("web", "index.html").toAbsolutePath().normalize();
        try {
            if (!Files.exists(file)) {
                return HttpResponse.html(500, errorPage(500, "Thiếu web/index.html",
                        "Không tìm thấy <code>" + escapeHtml(file.toString()) + "</code>."));
            }
            return HttpResponse.html(200, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return HttpResponse.html(500, errorPage(500, "Không đọc được web client",
                    escapeHtml(safeMessage(e))));
        }
    }

    // ------------------------------------------------------------------
    // Proxy JSON cho web client
    // ------------------------------------------------------------------

    private HttpResponse handleProxy(HttpRequest request) {
        String target = queryParam(request.query(), "url");
        if (target == null || target.isBlank()) {
            return HttpResponse.json(400, errorBody("Thiếu tham số ?url=<địa chỉ cần tải>."));
        }
        String requested = target.trim();
        if (requested.startsWith("/")) {
            requested = baseUrl() + requested;
        } else if (!requested.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            requested = "http://" + requested;
        }

        try {
            NetworkClient.Response response = NetworkClient.send(requested, "GET");
            String title = NetworkClient.titleOf(response.body());
            boolean html = response.isHtml();
            String prepared = html
                    ? NetworkClient.prepareHtml(response.body(), response.finalUrl(), false)
                    : "";
            NetworkClient.Analysis analysis = NetworkClient.analyze(
                    html ? response.body() : "", response.bodyByteLength());

            boolean historySaved = false;
            String historyError = "";
            try {
                database.recordHistory(requested, response.finalUrl(), title,
                        response.statusCode(), response.contentType(),
                        response.bodyByteLength(), response.elapsedMillis(),
                        response.targetIp(), response.targetPort());
                historySaved = true;
            } catch (DatabaseManager.DatabaseException e) {
                historyError = safeMessage(e);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("requestedUrl", requested);
            payload.put("finalUrl", response.finalUrl());
            payload.put("time", response.timeText());
            payload.put("ip", response.targetIp());
            payload.put("port", response.targetPort());
            // Hợp đồng JSON: status là chuỗi "200 OK" cho frontend,
            // statusCode là số để JavaScript xử lý class/màu sắc.
            payload.put("status", response.statusCode() + " " + response.reason());
            payload.put("statusCode", response.statusCode());
            payload.put("statusText", response.statusCode() + " " + response.reason());
            payload.put("reason", response.reason());
            payload.put("httpVersion", response.httpVersion());
            payload.put("contentType", response.contentType());
            payload.put("bytes", response.bodyByteLength());
            payload.put("elapsedMs", response.elapsedMillis());
            payload.put("title", title);
            payload.put("isHtml", html);
            payload.put("html", prepared);
            payload.put("headers", response.headersAsText());
            payload.put("analysis", analysisMap(analysis));
            payload.put("historySaved", historySaved);
            payload.put("historyError", historyError);
            payload.put("message", html ? "" : "Nội dung không phải HTML nên không hiển thị trong iframe.");
            return HttpResponse.json(200, payload);
        } catch (IOException e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", false);
            payload.put("requestedUrl", requested);
            payload.put("time", LocalTime.now().format(TIME_FORMAT));
            payload.put("ip", "");
            payload.put("port", 0);
            payload.put("status", "ERR Network Error");
            payload.put("statusCode", 0);
            payload.put("statusText", "ERR");
            payload.put("reason", "Network Error");
            payload.put("message", safeMessage(e));
            payload.put("html", "");
            payload.put("isHtml", false);
            payload.put("historySaved", false);
            return HttpResponse.json(200, payload);
        }
    }

    private static Map<String, Object> analysisMap(NetworkClient.Analysis analysis) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("p", analysis.paragraphs());
        map.put("div", analysis.divs());
        map.put("span", analysis.spans());
        map.put("img", analysis.images());
        map.put("a", analysis.links());
        map.put("chars", analysis.characters());
        map.put("bytes", analysis.bytes());
        return map;
    }

    private HttpResponse handleHistory() {
        try {
            List<DatabaseManager.History> history = database.recentHistory(100);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DatabaseManager.History entry : history) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", entry.id());
                row.put("time", entry.visitedAt() == null ? ""
                        : entry.visitedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                row.put("visitedAt", entry.visitedAt() == null ? ""
                        : entry.visitedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                row.put("url", entry.url());
                row.put("finalUrl", entry.finalUrl());
                row.put("title", entry.title());
                row.put("status", entry.statusCode());
                row.put("statusCode", entry.statusCode());
                row.put("statusText", statusText(entry.statusCode()));
                row.put("contentType", entry.contentType());
                row.put("bytes", entry.contentLength());
                row.put("elapsedMs", entry.elapsedMs());
                row.put("ip", toIpv4(entry.targetIp()));
                row.put("port", entry.targetPort());
                rows.add(row);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("count", rows.size());
            payload.put("history", rows);
            payload.put("exportedAt", LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return HttpResponse.json(200, payload);
        } catch (DatabaseManager.DatabaseException e) {
            return HttpResponse.json(503, errorBody(e.getMessage()));
        }
    }

    private HttpResponse handleHealth() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("server", SERVER_NAME);
        payload.put("status", running ? "running" : "stopped");
        payload.put("port", port);
        payload.put("baseUrl", baseUrl());
        payload.put("uptimeSeconds", uptimeSeconds());
        payload.put("uptime", uptimeText());
        payload.put("requests", requestCount());
        payload.put("activeConnections", activeConnections());
        payload.put("databaseEndpoint", database.endpoint());
        payload.put("databaseName", database.databaseName());
        payload.put("serverTime", LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        try {
            payload.put("database", database.describe());
            payload.put("databaseOk", true);
        } catch (DatabaseManager.DatabaseException e) {
            payload.put("database", safeMessage(e));
            payload.put("databaseOk", false);
        }
        return HttpResponse.json(200, payload);
    }

    // ------------------------------------------------------------------
    // POST form
    // ------------------------------------------------------------------

    private HttpResponse postContact(HttpRequest request, DatabaseManager.Page page) {
        String body = new String(request.body(), StandardCharsets.UTF_8);
        Map<String, String> params = parseForm(body);
        String name = params.getOrDefault("ho_ten", "(trống)");
        String studentId = params.getOrDefault("ma_sv", "(trống)");
        String message = params.getOrDefault("noi_dung", "(trống)");

        String html = """
                <!DOCTYPE html>
                <html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Kết quả POST</title>
                <style>
                  :root{color-scheme:light}
                  body{font-family:'Segoe UI',Arial,sans-serif;margin:0;background:#f1f5f9;color:#0f172a}
                  .wrap{max-width:820px;margin:36px auto;padding:0 20px}
                  .card{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:28px;
                        box-shadow:0 12px 36px rgba(15,23,42,.08)}
                  .badge{display:inline-block;background:#dcfce7;color:#15803d;font-size:12px;
                         font-weight:800;padding:6px 12px;border-radius:999px}
                  h1{margin:14px 0 18px;font-size:26px}
                  table{width:100%;border-collapse:collapse;margin:18px 0}
                  th,td{border:1px solid #e2e8f0;padding:12px;text-align:left;vertical-align:top}
                  th{width:170px;background:#f8fafc;color:#475569;font-size:12px;text-transform:uppercase}
                  a{color:#2563eb;font-weight:700;text-decoration:none}
                  .hint{margin-top:18px;padding:14px 16px;background:#f8fafc;border-radius:10px;
                        color:#475569;font-size:14px}
                </style></head><body><div class="wrap"><div class="card">
                <span class="badge">POST · application/x-www-form-urlencoded</span>
                <h1>Đã nhận dữ liệu biểu mẫu</h1>
                <table>
                  <tr><th>Họ và tên</th><td>{{NAME}}</td></tr>
                  <tr><th>Mã sinh viên</th><td>{{STUDENT_ID}}</td></tr>
                  <tr><th>Nội dung</th><td>{{MESSAGE}}</td></tr>
                  <tr><th>Số byte body</th><td>{{BODY_BYTES}}</td></tr>
                </table>
                <div class="hint">Server đọc <code>Content-Length</code>, lấy đúng body từ socket,
                decode UTF-8 rồi trả trang kết quả này. Trang gốc: <code>{{PAGE_PATH}}</code>.</div>
                <p><a href="/lien-he">← Gửi lại</a> · <a href="/">Về trang chủ</a></p>
                </div></div></body></html>
                """.replace("{{NAME}}", escapeHtml(name))
                .replace("{{STUDENT_ID}}", escapeHtml(studentId))
                .replace("{{MESSAGE}}", escapeHtml(message))
                .replace("{{BODY_BYTES}}", Integer.toString(request.body().length))
                .replace("{{PAGE_PATH}}", escapeHtml(page.urlPath()));
        return HttpResponse.html(200, html);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                params.put(key, value);
            }
        }
        return params;
    }

    // ------------------------------------------------------------------
    // Đọc request
    // ------------------------------------------------------------------

    private static final class HttpProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        HttpProtocolException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static HttpRequest readRequest(InputStream in, String clientIp, int clientPort)
            throws IOException, HttpProtocolException {

        String requestLine = readLine(in, MAX_HEADER_BYTES);
        if (requestLine == null || requestLine.isBlank()) {
            throw new HttpProtocolException(400, "Request rỗng.");
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new HttpProtocolException(400, "Request line không hợp lệ.");
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String rawTarget = parts[1];
        String version = parts.length >= 3 ? parts[2] : "HTTP/1.1";

        Map<String, String> headers = new LinkedHashMap<>();
        int totalHeaderBytes = requestLine.length() + 2;
        while (true) {
            String line = readLine(in, MAX_HEADER_BYTES);
            if (line == null) {
                throw new HttpProtocolException(400, "Header bị cắt ngang.");
            }
            if (line.isEmpty()) {
                break;
            }
            totalHeaderBytes += line.length() + 2;
            if (totalHeaderBytes > MAX_HEADER_BYTES) {
                throw new HttpProtocolException(431, "Header quá lớn.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }

        int contentLength = 0;
        String lengthHeader = header(headers, "Content-Length");
        if (lengthHeader != null && !lengthHeader.isBlank()) {
            try {
                contentLength = Integer.parseInt(lengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new HttpProtocolException(400, "Content-Length không hợp lệ.");
            }
            if (contentLength < 0) {
                throw new HttpProtocolException(400, "Content-Length âm.");
            }
            if (contentLength > MAX_BODY_BYTES) {
                throw new HttpProtocolException(413, "Body vượt quá " + MAX_BODY_BYTES + " byte.");
            }
        }
        byte[] body = contentLength == 0 ? new byte[0] : in.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new HttpProtocolException(400, "Body ngắn hơn Content-Length.");
        }

        int question = rawTarget.indexOf('?');
        String rawPath = question < 0 ? rawTarget : rawTarget.substring(0, question);
        String rawQuery = question < 0 ? "" : rawTarget.substring(question + 1);
        String path;
        String query;
        try {
            path = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            query = rawQuery;
        } catch (IllegalArgumentException e) {
            throw new HttpProtocolException(400, "Đường dẫn không mã hóa hợp lệ.");
        }
        if (path.isEmpty()) {
            path = "/";
        }
        return new HttpRequest(method, rawTarget, path, query, version, headers, body,
                clientIp, clientPort);
    }

    private static String readLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > maxBytes) {
                throw new IOException("Dòng quá dài.");
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            if (key.equals(name)) {
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return value;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Gửi response
    // ------------------------------------------------------------------

    private long sendResponse(OutputStream out, String method, HttpResponse response)
            throws IOException {
        byte[] body = response.body == null ? new byte[0] : response.body;
        String now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);

        StringBuilder head = new StringBuilder(320)
                .append("HTTP/1.1 ").append(response.status).append(' ')
                .append(response.reason == null ? reasonOf(response.status) : response.reason)
                .append("\r\n")
                .append("Date: ").append(now).append("\r\n")
                .append("Server: ").append(SERVER_NAME).append("\r\n")
                .append("Content-Type: ").append(response.contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
        if (response.extraHeaders != null) {
            response.extraHeaders.forEach((name, value) -> {
                if (name != null && value != null
                        && !name.equalsIgnoreCase("Content-Type")
                        && !name.equalsIgnoreCase("Content-Length")
                        && !name.equalsIgnoreCase("Connection")) {
                    head.append(name).append(": ").append(value).append("\r\n");
                }
            });
        }
        head.append("\r\n");

        byte[] headerBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        out.write(headerBytes);
        if (!"HEAD".equals(method)) {
            out.write(body);
        }
        out.flush();
        return headerBytes.length + ("HEAD".equals(method) ? 0 : body.length);
    }

    private static boolean isReadable(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private HttpResponse methodNotAllowed(String method) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Allow", "GET, POST, HEAD");
        return new HttpResponse(405, "Method Not Allowed", "application/json; charset=UTF-8",
                GSON.toJson(errorBody("Phương thức " + method
                        + " không được hỗ trợ. Chỉ dùng GET, POST hoặc HEAD."))
                        .getBytes(StandardCharsets.UTF_8), headers);
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        return body;
    }

    private static String errorPage(int status, String title, String message) {
        return """
                <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%d · %s</title>
                <style>
                  :root{color-scheme:light}
                  body{font-family:'Segoe UI',Arial,sans-serif;margin:0;background:#f1f5f9;color:#0f172a}
                  .wrap{max-width:760px;margin:48px auto;padding:0 20px}
                  .card{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:30px;
                        box-shadow:0 14px 40px rgba(15,23,42,.08)}
                  .code{font-size:54px;font-weight:800;color:#2563eb;line-height:1}
                  h1{font-size:24px;margin:16px 0 8px}p{color:#475569;line-height:1.65}
                  code{background:#f1f5f9;padding:2px 6px;border-radius:6px}
                  a{display:inline-block;margin-top:16px;color:#2563eb;font-weight:700;text-decoration:none}
                </style></head><body><div class="wrap"><div class="card">
                <div class="code">%d</div><h1>%s</h1><p>%s</p>
                <a href="/">← Về trang chủ</a>
                </div></div></body></html>
                """.formatted(status, escapeHtml(title), status, escapeHtml(title), message);
    }

    private static String statusText(int status) {
        return status <= 0 ? "ERR" : status + " " + reasonOf(status);
    }

    private static String reasonOf(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "không rõ";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void log(String message) {
        if (listener != null) {
            try {
                listener.onLog("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message);
            } catch (RuntimeException ignored) {
                // Không để lỗi UI ảnh hưởng server.
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Đang dọn tài nguyên.
        }
    }
}
```

## `src/core/NetworkClient.java`

_HTTP client raw socket, parse response, rewrite link_

```java
package core;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP/1.1 client viết tay trên raw {@link Socket} / {@link SSLSocket}.
 *
 * <p>Không dùng {@code HttpURLConnection}, {@code HttpClient} hay thư viện HTTP.
 * Lớp này tự:
 * <ul>
 *   <li>Phân giải URL → {@link InetAddress} → IP thật và port thật.</li>
 *   <li>Build request line + headers + body; parse status line, headers, chunked, gzip.</li>
 *   <li>Theo dõi redirect 301/302/303/307/308 tối đa 5 lần.</li>
 *   <li>Rewrite link HTML thành {@code javascript:loadUrl(...)} và chèn bridge script.</li>
 *   <li>Phân tích nhanh số thẻ p/div/span/img/a cho UI.</li>
 * </ul>
 */
public final class NetworkClient {

    public static final int CONNECT_TIMEOUT_MS = 8_000;
    public static final int READ_TIMEOUT_MS = 15_000;
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;

    private static final int MAX_HEADER_LINE = 16 * 1024;
    private static final Set<String> METHODS = Set.of("GET", "POST", "HEAD");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern ANCHOR = Pattern.compile(
            "(?is)(<a\\b[^>]*?\\shref\\s*=\\s*)([\"'])(.*?)\\2");
    private static final Pattern HEAD_OPEN = Pattern.compile("(?is)<head\\b[^>]*>");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Lab7-RawSocket/2.0";

    public record Response(
            int statusCode,
            String reason,
            String httpVersion,
            Map<String, List<String>> headers,
            byte[] bodyBytes,
            String body,
            String finalUrl,
            String targetIp,
            int targetPort,
            LocalTime time,
            long elapsedMillis,
            String rawRequest) {

        public Response {
            bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes.clone();
            body = body == null ? "" : body;
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (headers != null) {
                headers.forEach((name, values) -> copy.put(name,
                        Collections.unmodifiableList(new ArrayList<>(values))));
            }
            headers = Collections.unmodifiableMap(copy);
            targetIp = targetIp == null ? "" : targetIp;
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return values.isEmpty() ? null : values.get(0);
                }
            }
            return null;
        }

        public String contentType() {
            String value = header("Content-Type");
            return value == null ? "" : value;
        }

        public int bodyByteLength() {
            return bodyBytes.length;
        }

        public boolean isHtml() {
            String type = contentType().toLowerCase(Locale.ROOT);
            return type.contains("text/html") || type.contains("application/xhtml");
        }

        public String timeText() {
            return time == null ? "" : time.format(TIME_FORMAT);
        }

        public String headersAsText() {
            StringBuilder text = new StringBuilder();
            headers.forEach((name, values) -> {
                if (name != null) {
                    for (String value : values) {
                        text.append(name).append(": ").append(value).append('\n');
                    }
                }
            });
            return text.toString();
        }
    }

    /** Thống kê thẻ HTML cho tab Phân tích. */
    public record Analysis(int paragraphs, int divs, int spans, int images,
            int links, int characters, int bytes) {
    }

    private NetworkClient() {
    }

    public static Response send(String url, String method) throws IOException {
        return send(url, method, Collections.emptyMap(), null, null);
    }

    /**
     * Gửi request raw socket.
     *
     * @param url          URL đầy đủ http/https
     * @param method       GET, POST hoặc HEAD
     * @param extraHeaders header bổ sung (null được phép)
     * @param body         body cho POST
     * @param contentType  Content-Type (null → tự chọn theo body)
     */
    public static Response send(String url, String method,
            Map<String, String> extraHeaders, String body, String contentType) throws IOException {

        Objects.requireNonNull(url, "url");
        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalizedMethod)) {
            throw new IOException("Chỉ hỗ trợ GET, POST và HEAD (nhận: " + method + ").");
        }

        String currentUrl = normalizeUrl(url);
        String currentMethod = normalizedMethod;
        byte[] currentBody = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        if ("POST".equals(currentMethod) && currentBody == null) {
            currentBody = new byte[0];
        }
        if (!"POST".equals(currentMethod)) {
            currentBody = null;
        }

        String currentContentType = contentType;
        if (currentBody != null && (currentContentType == null || currentContentType.isBlank())) {
            String trimmed = body == null ? "" : body.trim();
            currentContentType = (trimmed.startsWith("{") || trimmed.startsWith("["))
                    ? "application/json; charset=UTF-8"
                    : "application/x-www-form-urlencoded; charset=UTF-8";
        }

        LocalTime startedAt = LocalTime.now();
        long startedNanos = System.nanoTime();

        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                URI uri = parseUri(currentUrl);
                Response response = execute(uri, currentMethod, extraHeaders,
                        currentBody, currentContentType, startedAt);

                if (isRedirect(response.statusCode()) && redirect < MAX_REDIRECTS) {
                    String location = response.header("Location");
                    if (location != null && !location.isBlank()) {
                        URI resolved = uri.resolve(sanitizeLocation(location));
                        currentUrl = resolved.toString();
                        boolean toGet = response.statusCode() == 303
                                || ((response.statusCode() == 301 || response.statusCode() == 302)
                                        && "POST".equals(currentMethod));
                        if (toGet) {
                            currentMethod = "GET";
                            currentBody = null;
                            currentContentType = null;
                        }
                        continue;
                    }
                }

                long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
                return new Response(response.statusCode(), response.reason(),
                        response.httpVersion(), response.headers(), response.bodyBytes(),
                        response.body(), currentUrl, response.targetIp(), response.targetPort(),
                        startedAt, elapsed, response.rawRequest());
            }
            throw new IOException("Vượt quá " + MAX_REDIRECTS + " lần chuyển hướng.");
        } catch (SocketTimeoutException e) {
            throw new IOException("Hết thời gian chờ (connect " + CONNECT_TIMEOUT_MS
                    + " ms, read " + READ_TIMEOUT_MS + " ms).", e);
        } catch (UnknownHostException e) {
            throw new IOException("Không phân giải được hostname: " + currentUrl, e);
        } catch (ConnectException e) {
            throw new IOException("Không kết nối được tới server: " + currentUrl, e);
        } catch (SSLException e) {
            throw new IOException("Lỗi TLS/HTTPS: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Thực thi một request
    // ------------------------------------------------------------------

    private static Response execute(URI uri, String method, Map<String, String> extraHeaders,
            byte[] body, String contentType, LocalTime startedAt) throws IOException {

        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL không có hostname hợp lệ: " + uri);
        }
        int port = uri.getPort() >= 0 ? uri.getPort()
                : ("https".equals(scheme) ? 443 : 80);

        InetAddress address = resolveIpv4(host);
        String targetIp = address.getHostAddress();

        Map<String, String> headers = buildHeaders(uri, host, port, method,
                body, contentType, extraHeaders);
        String rawRequest = buildRawRequest(uri, method, headers, body);

        try (Socket socket = openSocket(scheme, address, host, port)) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(buildRequestHead(uri, method, headers));
            if (body != null && body.length > 0) {
                out.write(body);
            }
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(in);
            if (statusLine == null || statusLine.isBlank()) {
                throw new IOException("Server đóng kết nối mà không trả HTTP status line.");
            }
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) {
                throw new IOException("Status line không hợp lệ: " + statusLine);
            }
            int status;
            try {
                status = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IOException("Mã trạng thái không hợp lệ: " + statusLine);
            }
            String reason = parts.length == 3 ? parts[2].trim() : "";
            String version = parts[0];

            Map<String, List<String>> responseHeaders = readHeaders(in);
            boolean noBody = "HEAD".equals(method) || status == 204 || status == 304;
            byte[] rawBody = noBody ? new byte[0] : readBody(in, responseHeaders);
            byte[] decoded = decode(rawBody, firstHeader(responseHeaders, "Content-Encoding"));
            String text = new String(decoded, charsetOf(firstHeader(responseHeaders, "Content-Type")));

            return new Response(status, reason, version, responseHeaders, decoded, text,
                    uri.toString(), targetIp, port, startedAt, 0, rawRequest);
        }
    }

    private static InetAddress resolveIpv4(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address;
            }
        }
        throw new UnknownHostException(
                "Host " + host + " không có bản ghi IPv4, không thể kết nối theo yêu cầu IPv4.");
    }

    private static Socket openSocket(String scheme, InetAddress address, String host, int port)
            throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(READ_TIMEOUT_MS);
            raw.setTcpNoDelay(true);

            if (!"https".equals(scheme)) {
                return raw;
            }
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.setSoTimeout(READ_TIMEOUT_MS);
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            try {
                raw.close();
            } catch (IOException ignored) {
                // Đang xử lý lỗi chính.
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Build request
    // ------------------------------------------------------------------

    private static Map<String, String> buildHeaders(URI uri, String host, int port, String method,
            byte[] body, String contentType, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        boolean defaultPort = ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        headers.put("Host", defaultPort ? host : host + ":" + port);
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "vi,en-US;q=0.9,en;q=0.8");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Connection", "close");

        if (body != null) {
            headers.put("Content-Type", contentType == null
                    ? "application/x-www-form-urlencoded; charset=UTF-8" : contentType);
            headers.put("Content-Length", Integer.toString(body.length));
        }
        if (extraHeaders != null) {
            extraHeaders.forEach((name, value) -> {
                if (name != null && value != null) {
                    headers.put(name, value);
                }
            });
        }
        return headers;
    }

    private static byte[] buildRequestHead(URI uri, String method, Map<String, String> headers) {
        String target = uri.getRawPath();
        if (target == null || target.isBlank()) {
            target = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            target += "?" + uri.getRawQuery();
        }
        StringBuilder head = new StringBuilder(256)
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        return head.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String buildRawRequest(URI uri, String method,
            Map<String, String> headers, byte[] body) {
        String target = uri.getRawPath();
        if (target == null || target.isBlank()) {
            target = "/";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            target += "?" + uri.getRawQuery();
        }
        StringBuilder text = new StringBuilder(256)
                .append(method).append(' ').append(target).append(" HTTP/1.1\n");
        headers.forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
        if (body != null && body.length > 0) {
            text.append('\n').append(new String(body, StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // Parse response
    // ------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > MAX_HEADER_LINE) {
                throw new IOException("Header vượt quá " + MAX_HEADER_LINE + " byte.");
            }
        }
        if (value == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            total += line.length() + 2;
            if (total > 64 * 1024) {
                throw new IOException("Tổng header response quá lớn.");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    private static byte[] readBody(InputStream in, Map<String, List<String>> headers)
            throws IOException {
        String transfer = firstHeader(headers, "Transfer-Encoding");
        if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String length = firstHeader(headers, "Content-Length");
        if (length == null || length.isBlank()) {
            return in.readAllBytes();
        }
        long size;
        try {
            size = Long.parseLong(length.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Content-Length không hợp lệ: " + length);
        }
        if (size < 0 || size > MAX_BODY_BYTES) {
            throw new IOException("Body quá lớn (" + size + " byte).");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) {
            throw new IOException("Body ngắn hơn Content-Length.");
        }
        return data;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("Chunked body bị cắt ngang.");
            }
            int semicolon = line.indexOf(';');
            String sizeText = (semicolon < 0 ? line : line.substring(0, semicolon)).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Kích thước chunk không hợp lệ: " + sizeText);
            }
            if (size < 0 || body.size() + size > MAX_BODY_BYTES) {
                throw new IOException("Chunked body quá lớn.");
            }
            if (size == 0) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) {
                        break;
                    }
                }
                return body.toByteArray();
            }
            body.write(in.readNBytes(size));
            int cr = in.read();
            int lf = in.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException("Chunk phải kết thúc bằng CRLF.");
            }
        }
    }

    private static byte[] decode(byte[] data, String encoding) throws IOException {
        if (data.length == 0 || encoding == null || encoding.isBlank()) {
            return data;
        }
        String value = encoding.toLowerCase(Locale.ROOT);
        if (value.contains("gzip")) {
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
                return in.readAllBytes();
            }
        }
        if (value.contains("deflate")) {
            try (InputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(data))) {
                return in.readAllBytes();
            }
        }
        return data;
    }

    // ------------------------------------------------------------------
    // HTML utilities
    // ------------------------------------------------------------------

    /** Rewrite toàn bộ link <a href> thành javascript:loadUrl('...') và chèn bridge script. */
    public static String prepareHtml(String html, String baseUrl, boolean desktop) {
        String source = html == null ? "" : html;
        String base = baseUrl == null ? "" : baseUrl;

        source = rewriteAnchors(source, base);
        source = injectBase(source, base);
        source = injectBridge(source, desktop);
        return source;
    }

    private static String rewriteAnchors(String html, String baseUrl) {
        Matcher matcher = ANCHOR.matcher(html);
        StringBuffer out = new StringBuffer(html.length() + 128);
        while (matcher.find()) {
            String href = matcher.group(3) == null ? "" : matcher.group(3).trim();
            String lower = href.toLowerCase(Locale.ROOT);
            if (href.isEmpty() || lower.startsWith("javascript:") || lower.startsWith("#")
                    || lower.startsWith("mailto:") || lower.startsWith("tel:")
                    || lower.startsWith("data:") || lower.startsWith("about:")
                    || lower.startsWith("blob:")) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String absolute = absolutize(baseUrl, href);
            String replacement = matcher.group(1) + "\"javascript:loadUrl('"
                    + jsEscape(absolute) + "')\"";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String injectBase(String html, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || Pattern.compile("(?is)<base\\b").matcher(html).find()) {
            return html;
        }
        String tag = "<base href=\"" + attrEscape(baseUrl) + "\">";
        Matcher head = HEAD_OPEN.matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + tag + html.substring(head.end());
        }
        return tag + html;
    }

    private static String injectBridge(String html, boolean desktop) {
        String bridge = desktop
                ? "<script>function loadUrl(u){window.location.href=String(u);}</script>"
                : "<script>function loadUrl(u){try{parent.postMessage({type:'lab7:navigate',"
                        + "url:String(u)},'*');}catch(e){window.location.href=String(u);}}</script>";

        if (Pattern.compile("(?is)</head\\s*>").matcher(html).find()) {
            return html.replaceFirst("(?is)</head\\s*>", Matcher.quoteReplacement(bridge + "</head>"));
        }
        return bridge + html;
    }

    public static String titleOf(String html) {
        if (html == null) {
            return "";
        }
        Matcher matcher = TITLE.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("(?s)<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replaceAll("\\s+", " ").trim();
    }

    public static Analysis analyze(String html, int bytes) {
        String source = html == null ? "" : html;
        return new Analysis(
                countTag(source, "p"),
                countTag(source, "div"),
                countTag(source, "span"),
                countTag(source, "img"),
                countTag(source, "a"),
                source.length(),
                bytes);
    }

    private static int countTag(String html, String tag) {
        Matcher matcher = Pattern.compile("(?i)<" + tag + "\\b").matcher(html);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Tiện ích URL
    // ------------------------------------------------------------------

    public static String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "http://localhost:8080/";
        }
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            value = "http://" + value;
        }
        return value;
    }

    public static URI parseUri(String url) throws IOException {
        try {
            URI uri = URI.create(normalizeUrl(url));
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IOException("URL không có hostname hợp lệ: " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("URL không hợp lệ: " + url, e);
        }
    }

    private static String absolutize(String baseUrl, String href) {
        try {
            return parseUri(baseUrl).resolve(href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private static String sanitizeLocation(String location) {
        return location.replace(" ", "%20").trim();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            }
        }
        return null;
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String name = trimmed.substring("charset=".length()).replace("\"", "").trim();
                    try {
                        return Charset.forName(name);
                    } catch (Exception ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String jsEscape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String attrEscape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
```

## `src/core/DatabaseManager.java`

_MySQL lab7_web_server, lịch sử và export JSON_

```java
package core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Toàn bộ tầng dữ liệu của Lab 7.
 *
 * <p>Kết nối MySQL/XAMPP ở cổng <b>3307</b> (root, mật khẩu rỗng), tự tạo database
 * {@code lab7_web_server}, hai bảng {@code web_pages} và {@code web_history}, seed
 * 4 trang mẫu, và tự động đồng bộ ra:
 * <ul>
 *   <li>{@code web_data.json} — snapshot bảng web_pages</li>
 *   <li>{@code web_history.json} — snapshot bảng web_history</li>
 * </ul>
 */
public final class DatabaseManager {

    public static final int MYSQL_PORT = 3307;
    public static final String DEFAULT_CONTENT_TYPE = "text/html; charset=UTF-8";

    public static final String DEFAULT_URL =
            "jdbc:mysql://localhost:" + MYSQL_PORT + "/lab7_web_server"
                    + "?useUnicode=true"
                    + "&characterEncoding=UTF-8"
                    + "&connectionCollation=utf8mb4_unicode_ci"
                    + "&serverTimezone=Asia/Ho_Chi_Minh"
                    + "&useSSL=false"
                    + "&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=3000"
                    + "&socketTimeout=8000";

    private static final Pattern MYSQL_URL = Pattern.compile(
            "^jdbc:mysql://([^/:?]+)(?::(\\d+))?/([^?]*)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    private static final String CREATE_PAGES = """
            CREATE TABLE IF NOT EXISTS web_pages (
                id            BIGINT       NOT NULL AUTO_INCREMENT,
                url_path      VARCHAR(255) NOT NULL,
                title         VARCHAR(255) NOT NULL,
                content       MEDIUMTEXT   NOT NULL,
                content_type  VARCHAR(128) NOT NULL DEFAULT 'text/html; charset=UTF-8',
                created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_web_pages_url_path (url_path)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS web_history (
                id             BIGINT       NOT NULL AUTO_INCREMENT,
                url            TEXT         NOT NULL,
                final_url      TEXT         NOT NULL,
                title          VARCHAR(512) NULL,
                status_code    INT          NOT NULL,
                content_type   VARCHAR(255) NULL,
                content_length BIGINT       NOT NULL DEFAULT 0,
                elapsed_ms     BIGINT       NOT NULL DEFAULT 0,
                target_ip      VARCHAR(64)  NULL,
                target_port    INT          NOT NULL DEFAULT 0,
                visited_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_web_history_visited (visited_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    /** Một trang động trong bảng {@code web_pages}. */
    public record Page(long id, String urlPath, String title, String content,
            String contentType, LocalDateTime createdAt, LocalDateTime updatedAt) {

        public Page {
            urlPath = normalizePath(urlPath);
            title = title == null ? "" : title.trim();
            content = content == null ? "" : content;
            contentType = (contentType == null || contentType.isBlank())
                    ? DEFAULT_CONTENT_TYPE : contentType.trim();
        }

        public static Page draft(String path, String title, String content) {
            return new Page(0L, path, title, content, DEFAULT_CONTENT_TYPE, null, null);
        }
    }

    /** Một dòng lịch sử trong bảng {@code web_history}. */
    public record History(long id, String url, String finalUrl, String title,
            int statusCode, String contentType, long contentLength, long elapsedMs,
            String targetIp, int targetPort, LocalDateTime visitedAt) {
    }

    /** Snapshot dữ liệu để desktop refresh mà không phải đọc từng bảng riêng. */
    public record DataSnapshot(List<Page> pages, List<History> history,
            String pagesJson, String historyJson) {
    }

    public static final class DatabaseException extends Exception {
        private static final long serialVersionUID = 1L;

        public DatabaseException(String message) {
            super(message);
        }

        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Path pagesJson = Path.of("web_data.json").toAbsolutePath().normalize();
    private final Path historyJson = Path.of("web_history.json").toAbsolutePath().normalize();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DatabaseManager() {
        this(env("LAB7_DB_URL", DEFAULT_URL),
                env("LAB7_DB_USER", "root"),
                env("LAB7_DB_PASSWORD", ""));
    }

    public DatabaseManager(String jdbcUrl, String username, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl").trim();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    // ------------------------------------------------------------------
    // Khởi tạo
    // ------------------------------------------------------------------

    public synchronized void initialize() throws DatabaseException {
        ensureDatabase();
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_PAGES);
                statement.executeUpdate(CREATE_HISTORY);
            }
            ensureColumn(connection, "web_history", "target_ip", "VARCHAR(64) NULL");
            ensureColumn(connection, "web_history", "target_port", "INT NOT NULL DEFAULT 0");
            if (count(connection, "web_pages") == 0) {
                for (Page page : defaultPages()) {
                    insert(connection, page);
                }
            }
        } catch (SQLException e) {
            throw translate("Khởi tạo database", e);
        }
        exportPages();
        exportHistory();
    }

    /** Kiểm tra kết nối nhanh cho dashboard. */
    public String describe() throws DatabaseException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT VERSION(), DATABASE()")) {
            if (rs.next()) {
                return "MySQL " + rs.getString(1) + " · " + rs.getString(2) + " · " + endpoint();
            }
            return "MySQL · " + endpoint();
        } catch (SQLException e) {
            throw translate("Kiểm tra kết nối", e);
        }
    }

    public String endpoint() {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (matcher.matches()) {
            String port = matcher.group(2) == null ? Integer.toString(MYSQL_PORT) : matcher.group(2);
            return matcher.group(1) + ":" + port;
        }
        return "localhost:" + MYSQL_PORT;
    }

    // ------------------------------------------------------------------
    // web_pages
    // ------------------------------------------------------------------

    public Page findByPath(String path) throws DatabaseException {
        String sql = "SELECT id, url_path, title, content, content_type, created_at, updated_at "
                + "FROM web_pages WHERE url_path = ? LIMIT 1";
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizePath(path));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPage(rs) : null;
            }
        } catch (SQLException e) {
            throw translate("Đọc trang " + path, e);
        }
    }

    public List<Page> findAllPages() throws DatabaseException {
        String sql = "SELECT id, url_path, title, content, content_type, created_at, updated_at "
                + "FROM web_pages ORDER BY id";
        List<Page> pages = new ArrayList<>();
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                pages.add(mapPage(rs));
            }
            return pages;
        } catch (SQLException e) {
            throw translate("Đọc danh sách trang", e);
        }
    }

    public synchronized Page save(Page page) throws DatabaseException {
        try (Connection connection = open()) {
            if (page.id() > 0 && exists(connection, page.id())) {
                String sql = "UPDATE web_pages SET url_path=?, title=?, content=?, content_type=? WHERE id=?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, page.urlPath());
                    ps.setString(2, page.title());
                    ps.setString(3, page.content());
                    ps.setString(4, page.contentType());
                    ps.setLong(5, page.id());
                    ps.executeUpdate();
                }
            } else {
                return insert(connection, page);
            }
        } catch (SQLException e) {
            throw translate("Lưu trang " + page.urlPath(), e);
        }
        exportPages();
        return findByPath(page.urlPath());
    }

    public synchronized boolean delete(long id) throws DatabaseException {
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM web_pages WHERE id=?")) {
            ps.setLong(1, id);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) {
                exportPages();
            }
            return deleted;
        } catch (SQLException e) {
            throw translate("Xóa trang id=" + id, e);
        }
    }

    // ------------------------------------------------------------------
    // web_history
    // ------------------------------------------------------------------

    public synchronized void recordHistory(String url, String finalUrl, String title,
            int statusCode, String contentType, long contentLength, long elapsedMs,
            String targetIp, int targetPort) throws DatabaseException {
        String sql = "INSERT INTO web_history (url, final_url, title, status_code, content_type, "
                + "content_length, elapsed_ms, target_ip, target_port) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setString(2, finalUrl);
            ps.setString(3, title);
            ps.setInt(4, statusCode);
            ps.setString(5, contentType);
            ps.setLong(6, contentLength);
            ps.setLong(7, elapsedMs);
            ps.setString(8, targetIp);
            ps.setInt(9, targetPort);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw translate("Ghi lịch sử duyệt web", e);
        }
        refreshData();
        exportJson();
    }

    public List<History> recentHistory(int limit) throws DatabaseException {
        String sql = "SELECT id, url, final_url, title, status_code, content_type, "
                + "content_length, elapsed_ms, target_ip, target_port, visited_at "
                + "FROM web_history ORDER BY id DESC LIMIT ?";
        List<History> history = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(new History(
                            rs.getLong("id"),
                            rs.getString("url"),
                            rs.getString("final_url"),
                            rs.getString("title"),
                            rs.getInt("status_code"),
                            rs.getString("content_type"),
                            rs.getLong("content_length"),
                            rs.getLong("elapsed_ms"),
                            rs.getString("target_ip"),
                            rs.getInt("target_port"),
                            rs.getTimestamp("visited_at") == null ? null
                                    : rs.getTimestamp("visited_at").toLocalDateTime()));
                }
            }
            return history;
        } catch (SQLException e) {
            throw translate("Đọc lịch sử duyệt web", e);
        }
    }

    // ------------------------------------------------------------------
    // Làm mới dữ liệu cho giao diện desktop
    // ------------------------------------------------------------------

    public synchronized DataSnapshot refreshData() throws DatabaseException {
        List<Page> pages = findAllPages();
        List<History> history = recentHistory(300);
        return new DataSnapshot(pages, history, readPagesJson(), readHistoryJson());
    }

    /** Ghi lại web_history.json chuẩn UTF-8 và trả về đường dẫn file. */
    public synchronized String exportJson() throws DatabaseException {
        exportHistory();
        return historyJson.toString();
    }

    // ------------------------------------------------------------------
    // Export JSON
    // ------------------------------------------------------------------

    public synchronized String exportPages() throws DatabaseException {
        List<Page> pages = findAllPages();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", LocalDateTime.now().format(dateTimeFormat));
        root.put("database", databaseName());
        root.put("table", "web_pages");
        root.put("count", pages.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Page page : pages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", page.id());
            row.put("url_path", page.urlPath());
            row.put("title", page.title());
            row.put("content_type", page.contentType());
            row.put("content", page.content());
            row.put("created_at", page.createdAt() == null ? null
                    : page.createdAt().format(dateTimeFormat));
            row.put("updated_at", page.updatedAt() == null ? null
                    : page.updatedAt().format(dateTimeFormat));
            rows.add(row);
        }
        root.put("pages", rows);
        writeJson(pagesJson, root);
        return pagesJson.toString();
    }

    public synchronized String exportHistory() throws DatabaseException {
        List<History> history = recentHistory(200);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exportedAt", LocalDateTime.now().format(dateTimeFormat));
        root.put("database", databaseName());
        root.put("table", "web_history");
        root.put("count", history.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (History entry : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("url", entry.url());
            row.put("final_url", entry.finalUrl());
            row.put("title", entry.title());
            row.put("status_code", entry.statusCode());
            row.put("content_type", entry.contentType());
            row.put("content_length", entry.contentLength());
            row.put("elapsed_ms", entry.elapsedMs());
            row.put("target_ip", entry.targetIp());
            row.put("target_port", entry.targetPort());
            row.put("visited_at", entry.visitedAt() == null ? null
                    : entry.visitedAt().format(dateTimeFormat));
            rows.add(row);
        }
        root.put("history", rows);
        writeJson(historyJson, root);
        return historyJson.toString();
    }

    public String readPagesJson() {
        return readJson(pagesJson);
    }

    public String readHistoryJson() {
        return readJson(historyJson);
    }

    public Path pagesJsonPath() {
        return pagesJson;
    }

    public Path historyJsonPath() {
        return historyJson;
    }

    public String databaseName() {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (matcher.matches() && !matcher.group(3).isBlank()) {
            return matcher.group(3);
        }
        return "lab7_web_server";
    }

    public static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String path = raw.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Nội bộ
    // ------------------------------------------------------------------

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void ensureDatabase() throws DatabaseException {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (!matcher.matches()) {
            return;
        }
        String host = matcher.group(1);
        String port = matcher.group(2) == null ? Integer.toString(MYSQL_PORT) : matcher.group(2);
        String database = matcher.group(3);
        String options = matcher.group(4) == null ? "" : matcher.group(4);
        if (database == null || database.isBlank()) {
            return;
        }
        String serverUrl = "jdbc:mysql://" + host + ":" + port + "/" + options;
        String sql = "CREATE DATABASE IF NOT EXISTS `" + database.replace("`", "``")
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (!message.contains("access denied") && !message.contains("unknown database")) {
                throw translate("Tạo database " + database, e);
            }
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String definition) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // Cột đã tồn tại.
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static boolean exists(Connection connection, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM web_pages WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Page insert(Connection connection, Page page) throws SQLException {
        String sql = "INSERT INTO web_pages (url_path, title, content, content_type) VALUES (?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, page.urlPath());
            ps.setString(2, page.title());
            ps.setString(3, page.content());
            ps.setString(4, page.contentType());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new Page(id, page.urlPath(), page.title(), page.content(),
                        page.contentType(), LocalDateTime.now(), LocalDateTime.now());
            }
        }
    }

    private static Page mapPage(ResultSet rs) throws SQLException {
        return new Page(
                rs.getLong("id"),
                rs.getString("url_path"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("content_type"),
                rs.getTimestamp("created_at") == null ? null
                        : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at") == null ? null
                        : rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private void writeJson(Path target, Object value) throws DatabaseException {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DatabaseException("Không ghi được file JSON " + target + ": " + e.getMessage(), e);
        }
    }

    private static String readJson(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8)
                    : "File chưa tồn tại: " + file;
        } catch (IOException e) {
            return "Không đọc được " + file + ": " + e.getMessage();
        }
    }

    private DatabaseException translate(String action, SQLException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String lower = raw.toLowerCase(Locale.ROOT);
        String hint;
        if (lower.contains("communications link failure") || lower.contains("connection refused")
                || lower.contains("connect timed out") || "08S01".equals(e.getSQLState())) {
            hint = "Không kết nối được MySQL tại " + endpoint()
                    + ". Mở XAMPP Control Panel và Start MySQL (cổng 3307).";
        } else if (lower.contains("access denied")) {
            hint = "MySQL từ chối đăng nhập. Kiểm tra user 'root' và mật khẩu rỗng.";
        } else if (lower.contains("unknown database")) {
            hint = "Chưa có database lab7_web_server. Hãy chạy bằng user root để tự tạo.";
        } else if (lower.contains("no suitable driver")) {
            hint = "Thiếu MySQL Connector/J trong thư mục libs.";
        } else {
            hint = raw;
        }
        return new DatabaseException(action + " thất bại. " + hint, e);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ------------------------------------------------------------------
    // Dữ liệu mẫu
    // ------------------------------------------------------------------

    private static List<Page> defaultPages() {
        String style = """
                <style>
                  :root{color-scheme:light}
                  body{font-family:'Segoe UI',Arial,sans-serif;margin:0;background:#f1f5f9;color:#0f172a}
                  .wrap{max-width:820px;margin:36px auto;padding:0 20px}
                  .card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:28px;box-shadow:0 8px 24px rgba(15,23,42,.06)}
                  .badge{display:inline-block;background:#dbeafe;color:#1d4ed8;font-size:12px;font-weight:700;padding:5px 10px;border-radius:999px}
                  h1{margin:14px 0 10px;font-size:26px}h2{margin-top:26px;font-size:18px}
                  p,li{line-height:1.65;color:#334155}a{color:#2563eb;font-weight:600}
                  table{width:100%;border-collapse:collapse;margin-top:14px}
                  th,td{border:1px solid #e2e8f0;padding:10px 12px;text-align:left}
                  th{background:#f8fafc;color:#475569;font-size:12px;text-transform:uppercase}
                  .ok{color:#15803d;font-weight:700}
                  form{margin-top:16px}label{display:block;margin:14px 0 6px;font-size:13px;font-weight:700}
                  input,textarea{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid #cbd5e1;border-radius:8px;font:inherit}
                  button{margin-top:16px;padding:10px 20px;border:0;border-radius:8px;background:#2563eb;color:#fff;font-weight:700;cursor:pointer}
                  code{background:#f1f5f9;padding:2px 6px;border-radius:6px}
                </style>
                """;

        String index = """
                <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Lab 7 - Web Server từ MySQL</title>""" + style + """
                </head><body><div class="wrap"><div class="card">
                <span class="badge">DỮ LIỆU LẤY TỪ MYSQL · BẢNG web_pages</span>
                <h1>Lab 7 — Web Server &amp; Browser</h1>
                <p>Trang bạn đang xem <b>không phải file HTML tĩnh</b>. Nội dung được lưu trong
                MySQL (XAMPP, cổng 3307) và đọc ra theo từng request HTTP/1.1 qua raw TCP socket.</p>
                <h2>Các trang mẫu</h2><ul>
                  <li><a href="/gioi-thieu">/gioi-thieu</a> — kiến trúc hệ thống</li>
                  <li><a href="/sinh-vien">/sinh-vien</a> — bảng dữ liệu động</li>
                  <li><a href="/lien-he">/lien-he</a> — form POST UTF-8</li>
                </ul>
                <h2>Kiểm tra nhanh</h2>
                <p><code>curl -i http://localhost:8080/</code><br>
                <code>curl -I http://localhost:8080/sinh-vien</code><br>
                <code>curl -i -X POST -d "ho_ten=Nguyen%20Van%20A" http://localhost:8080/lien-he</code></p>
                </div></div></body></html>
                """;

        String about = """
                <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Giới thiệu kiến trúc</title>""" + style + """
                </head><body><div class="wrap"><div class="card">
                <span class="badge">KIẾN TRÚC 3 LỚP</span><h1>Giới thiệu hệ thống</h1>
                <table><tr><th>Lớp</th><th>Thành phần</th><th>Nhiệm vụ</th></tr>
                  <tr><td>Network</td><td><code>NetworkServer</code>, <code>NetworkClient</code></td><td>Raw TCP socket, HTTP/1.1</td></tr>
                  <tr><td>Data</td><td><code>DatabaseManager</code></td><td>MySQL, CRUD, export JSON</td></tr>
                  <tr><td>UI</td><td><code>MainView</code></td><td>Dashboard, Browser, Database</td></tr>
                </table>
                <h2>Điểm nhấn</h2><ul>
                  <li>Tự đọc request line, header, body bằng InputStream.</li>
                  <li>UTF-8 xuyên suốt; socket đóng bằng try-with-resources.</li>
                  <li>Mỗi thao tác ghi DB đều export JSON.</li>
                  <li>Link trong HTML được rewrite thành <code>javascript:loadUrl(...)</code>.</li>
                </ul><p><a href="/">&larr; Về trang chủ</a></p>
                </div></div></body></html>
                """;

        String students = """
                <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Danh sách sinh viên</title>""" + style + """
                </head><body><div class="wrap"><div class="card">
                <span class="badge">BẢNG DỮ LIỆU ĐỘNG</span><h1>Danh sách sinh viên</h1>
                <table><tr><th>STT</th><th>Mã SV</th><th>Họ và tên</th><th>Lớp</th><th>Trạng thái</th></tr>
                  <tr><td>1</td><td>24IT001</td><td>Nguyễn Văn An</td><td>24IT1</td><td class="ok">Đang học</td></tr>
                  <tr><td>2</td><td>24IT002</td><td>Trần Thị Bích</td><td>24IT1</td><td class="ok">Đang học</td></tr>
                  <tr><td>3</td><td>24IT003</td><td>Lê Hoàng Nam</td><td>24IT2</td><td class="ok">Đang học</td></tr>
                  <tr><td>4</td><td>24IT277</td><td>Sinh viên Lab 7</td><td>24IT1</td><td class="ok">Hoàn thành</td></tr>
                </table><p><a href="/">&larr; Về trang chủ</a></p>
                </div></div></body></html>
                """;

        String contact = """
                <!DOCTYPE html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Gửi biểu mẫu POST</title>""" + style + """
                </head><body><div class="wrap"><div class="card">
                <span class="badge">HTTP POST</span><h1>Gửi biểu mẫu</h1>
                <p>Form gửi <code>application/x-www-form-urlencoded</code>. Server đọc body, decode UTF-8 và trả kết quả.</p>
                <form action="/lien-he" method="post">
                  <label for="ho_ten">Họ và tên</label><input id="ho_ten" name="ho_ten" value="Nguyễn Văn A" required>
                  <label for="ma_sv">Mã sinh viên</label><input id="ma_sv" name="ma_sv" value="24IT277" required>
                  <label for="noi_dung">Nội dung</label><textarea id="noi_dung" name="noi_dung" rows="3">Em gửi bài Lab 7.</textarea>
                  <button type="submit">Gửi bằng POST</button>
                </form><p><a href="/">&larr; Về trang chủ</a></p>
                </div></div></body></html>
                """;

        return List.of(
                Page.draft("/", "Lab 7 - Web Server từ MySQL", index),
                Page.draft("/gioi-thieu", "Giới thiệu kiến trúc", about),
                Page.draft("/sinh-vien", "Danh sách sinh viên", students),
                Page.draft("/lien-he", "Gửi biểu mẫu POST", contact));
    }
}
```

## `src/ui/MainView.java`

_Giao diện Enterprise Light Mode + Ikonli FontAwesome_

```java
package ui;

import core.DatabaseManager;
import core.NetworkClient;
import core.NetworkServer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Toàn bộ giao diện desktop của Lab 7 trong một file duy nhất.
 *
 * <p>Ba tab:
 * <ol>
 *   <li><b>Server</b> — bật/tắt, cổng, log, trạng thái MySQL, số request, bảng request.</li>
 *   <li><b>Browser</b> — thanh URL, WebView, thống kê Time/IP/Port/Status và bảng lịch sử
 *       của phiên duyệt hiện tại.</li>
 *   <li><b>Database</b> — lịch sử trong MySQL, xem JSON và xuất file.</li>
 * </ol>
 */
public final class MainView {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String DEFAULT_URL = "http://localhost:8080/gioi-thieu";

    private static final Color INK = Color.web("#343a40");
    private static final Color MUTED = Color.web("#6c757d");
    private static final Color BLUE = Color.web("#007bff");
    private static final Color DEEP_BLUE = Color.web("#0056b3");
    private static final Color GREEN = Color.web("#28a745");
    private static final Color AMBER = Color.web("#fd7e14");
    private static final Color RED = Color.web("#dc3545");
    private static final Color VIOLET = Color.web("#6f42c1");
    private static final Color WHITE = Color.web("#ffffff");

    private record BrowserHit(String time, String ip, int port, int status,
            String url, long millis) {
    }

    private record BrowserResult(NetworkClient.Response response, String preparedHtml,
            String title, NetworkClient.Analysis analysis, boolean historySaved) {
    }

    private record DbSnapshot(List<DatabaseManager.History> history, int pageCount,
            String pagesJson, String historyJson) {
    }

    private final DatabaseManager database = new DatabaseManager();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "lab7-ui-worker");
        thread.setDaemon(true);
        return thread;
    });

    private NetworkServer server;
    private Stage stage;
    private String cssUrl;

    private final Label clockLabel = new Label("--:--:--");
    private final Label serverPill = new Label("ĐANG TẮT");
    private final Label dbPill = new Label("DB: ĐANG KIỂM TRA");
    private final FontIcon dbPillIcon = icon(FontAwesomeSolid.DATABASE, 12, GREEN);
    private final FontIcon dbCardIcon = icon(FontAwesomeSolid.DATABASE, 26, VIOLET);
    private final FontIcon statusBadgeIcon = icon(FontAwesomeSolid.CHECK_CIRCLE, 15, MUTED);
    private final Label requestsValue = new Label("0");
    private final Label uptimeValue = new Label("00:00:00");
    private final Label activeValue = new Label("0");
    private final Label dbStatusValue = new Label("Đang kiểm tra");
    private final Label headerUptimeLabel = new Label("Uptime 00:00:00");
    private final Label dbInfoLabel = new Label("Chưa kiểm tra MySQL.");
    private final Label serverUrlLabel = new Label("Chưa bật server.");

    private final TextField portField = new TextField(Integer.toString(NetworkServer.DEFAULT_PORT));
    private final Button startButton = new Button("Bật server");
    private final Button stopButton = new Button("Tắt server");
    private final Button openWebButton = new Button("Mở Web Client");

    private final ObservableList<NetworkServer.RequestInfo> serverRows =
            FXCollections.observableArrayList();
    private final TextArea logArea = new TextArea();

    private final ComboBox<String> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField(DEFAULT_URL);
    private final TextField postBodyField = new TextField();
    private final Button goButton = new Button("SEND");
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final Button reloadButton = new Button("↻");
    private final WebView webView = new WebView();
    private final WebEngine webEngine = webView.getEngine();
    private final TextArea plainArea = new TextArea();
    private final Label timeValue = new Label("--:--:--");
    private final Label ipValue = new Label("—");
    private final Label portValue = new Label("—");
    private final Label statusValue = new Label("—");
    private final Label analysisLabel = new Label("p 0 · div 0 · span 0 · img 0 · a 0");
    private final ObservableList<BrowserHit> browserRows = FXCollections.observableArrayList();

    private final ObservableList<DatabaseManager.History> historyRows =
            FXCollections.observableArrayList();
    private final TableView<DatabaseManager.History> historyTable = new TableView<>();
    private final TextArea pagesJsonArea = new TextArea();
    private final TextArea historyJsonArea = new TextArea();
    private final Label pagesCountLabel = new Label("0 trang");
    private final Label dbNoticeLabel = new Label("Chưa tải dữ liệu.");

    private final List<String> backStack = new ArrayList<>();
    private int backIndex = -1;
    private int loadToken;
    private boolean suppressLocation;
    private String currentLoadedUrl = "";
    private TabPane mainTabs;

    public MainView() {
        server = new NetworkServer(database, new NetworkServer.Listener() {
            @Override
            public void onLog(String message) {
                appendLog(message);
            }

            @Override
            public void onRequest(NetworkServer.RequestInfo info) {
                Platform.runLater(() -> addServerRequest(info));
            }
        });
        startClock();
    }

    // ------------------------------------------------------------------
    // Cửa sổ chính
    // ------------------------------------------------------------------

    public void show(Stage stage) {
        // Bắt buộc: đặt encoding trước khi JavaFX dựng giao diện.
        System.setProperty("file.encoding", "UTF-8");
        this.stage = stage;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        mainTabs = buildTabs();
        root.setCenter(mainTabs);

        Scene scene = new Scene(root, 1320, 840);
        URL css = MainView.class.getResource("style.css");
        if (css == null) {
            css = MainView.class.getResource("/ui/style.css");
        }
        if (css != null) {
            cssUrl = css.toExternalForm();
            scene.getStylesheets().add(cssUrl);
        }

        stage.setTitle("HTTP Server & Client Dashboard · Lab 7");
        stage.setMinWidth(1120);
        stage.setMinHeight(740);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> shutdown());
        stage.setOnHidden(event -> shutdown());
        stage.show();

        showWelcome();
        appendLog("[HỆ THỐNG] Giao diện sẵn sàng. Bấm “Bật server” để mở cổng "
                + portField.getText() + ".");
        initializeDatabase();
    }

    private VBox buildHeader() {
        FontIcon logoIcon = icon(FontAwesomeSolid.SERVER, 24, WHITE);
        StackPane mark = new StackPane(logoIcon);
        mark.getStyleClass().add("brand-mark");

        Label title = new Label("HTTP SERVER & CLIENT DASHBOARD");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("Raw Socket · JavaFX · MySQL · Web Client");
        subtitle.getStyleClass().add("brand-subtitle");
        VBox titles = new VBox(2, title, subtitle);
        HBox brand = new HBox(12, mark, titles);
        brand.setAlignment(Pos.CENTER_LEFT);

        clockLabel.getStyleClass().add("clock");
        clockLabel.setGraphic(icon(FontAwesomeSolid.CLOCK, 12, DEEP_BLUE));
        headerUptimeLabel.getStyleClass().addAll("clock", "header-uptime");
        headerUptimeLabel.setGraphic(icon(FontAwesomeSolid.CLOCK, 12, DEEP_BLUE));
        dbPill.getStyleClass().addAll("pill", "pill-warn");
        dbPill.setGraphic(dbPillIcon);

        portField.getStyleClass().addAll("field", "header-port");
        portField.setPrefWidth(74);
        portField.setTooltip(new Tooltip("Cổng HTTP, mặc định 8080"));
        Label portCaption = new Label("Cổng");
        portCaption.getStyleClass().add("header-label");
        portCaption.setGraphic(icon(FontAwesomeSolid.PLUG, 11, BLUE));
        HBox portBox = new HBox(6, portCaption, portField);
        portBox.setAlignment(Pos.CENTER_LEFT);

        startButton.setText("Bật Server");
        stopButton.setText("Tắt Server");
        startButton.setGraphic(icon(FontAwesomeSolid.PLAY, 13, WHITE));
        stopButton.setGraphic(icon(FontAwesomeSolid.STOP, 13, WHITE));
        startButton.getStyleClass().add("button-success");
        stopButton.getStyleClass().add("button-danger");
        openWebButton.setText("Web Client");
        openWebButton.setGraphic(icon(FontAwesomeSolid.GLOBE, 13, DEEP_BLUE));
        openWebButton.getStyleClass().add("button-ghost");
        openWebButton.setDisable(true);
        stopButton.setDisable(true);

        startButton.setOnAction(event -> startServer());
        stopButton.setOnAction(event -> stopServer());
        openWebButton.setOnAction(event -> openWebClient());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(9, dbPill, headerUptimeLabel, clockLabel, portBox,
                startButton, stopButton, openWebButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(18, brand, spacer, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return new VBox(header);
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("main-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab dashboardTab = new Tab("Dashboard", buildServerTab());
        dashboardTab.setGraphic(icon(FontAwesomeSolid.CHART_BAR, 14, BLUE));
        Tab browserTab = new Tab("Client Browser", buildBrowserTab());
        browserTab.setGraphic(icon(FontAwesomeSolid.GLOBE, 14, BLUE));
        Tab databaseTab = new Tab("Database", buildDatabaseTab());
        databaseTab.setGraphic(icon(FontAwesomeSolid.DATABASE, 14, GREEN));

        tabs.getTabs().addAll(dashboardTab, browserTab, databaseTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> {
            if (index != null && index.intValue() == 2) {
                refreshDatabaseView(false);
            }
        });
        return tabs;
    }

    // ------------------------------------------------------------------
    // Tab 1 — Server
    // ------------------------------------------------------------------

    private VBox buildServerTab() {
        VBox tab = new VBox(14);
        tab.getStyleClass().add("tab-content");

        HBox metrics = new HBox(14,
                metricCard("Tổng Request", requestsValue, "metric-blue",
                        icon(FontAwesomeSolid.CHART_BAR, 26, BLUE), "metric-icon-blue"),
                metricCard("Uptime", uptimeValue, "metric-green",
                        icon(FontAwesomeSolid.CLOCK, 26, GREEN), "metric-icon-green"),
                metricCard("DB Status", dbStatusValue, "metric-violet",
                        dbCardIcon, "metric-icon-violet"),
                metricCard("Active Clients", activeValue, "metric-amber",
                        icon(FontAwesomeSolid.USERS, 26, AMBER), "metric-icon-amber"));
        for (var node : metrics.getChildren()) {
            HBox.setHgrow(node, Priority.ALWAYS);
        }
        setDbCard("Đang kiểm tra", "stat-neutral");

        serverPill.getStyleClass().addAll("pill", "pill-neutral");
        serverPill.setGraphic(icon(FontAwesomeSolid.SERVER, 11, DEEP_BLUE));
        serverUrlLabel.getStyleClass().add("muted");
        dbInfoLabel.getStyleClass().add("muted");
        dbInfoLabel.setWrapText(true);
        Label hint = new Label("Điều khiển server bằng nút Bật/Tắt ở góc phải header. "
                + "Cổng mặc định là 8080.");
        hint.getStyleClass().add("muted");
        hint.setGraphic(icon(FontAwesomeSolid.INFO_CIRCLE, 12, MUTED));
        HBox statusRow = new HBox(10, serverPill, spacer(), hint);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox controlCard = new VBox(9, statusRow, serverUrlLabel, dbInfoLabel);
        controlCard.getStyleClass().add("card");

        TableView<NetworkServer.RequestInfo> requestTable = new TableView<>(serverRows);
        requestTable.getStyleClass().add("data-table");
        requestTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        requestTable.setPlaceholder(new Label("Chưa có request nào."));
        requestTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().timeText(), 84));
        requestTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(data.getValue().clientIp()), 140));
        requestTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().clientPort()), 72));
        requestTable.getColumns().add(column("Method", FontAwesomeSolid.CODE,
                data -> data.getValue().method(), 78));
        requestTable.getColumns().add(column("Path", FontAwesomeSolid.LINK,
                data -> data.getValue().path(), 300));
        requestTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().status()), 90));
        VBox.setVgrow(requestTable, Priority.ALWAYS);
        VBox requestCard = card("Danh sách Request gần đây", FontAwesomeSolid.LIST, requestTable);
        VBox.setVgrow(requestCard, Priority.ALWAYS);

        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        VBox logCard = card("Nhật ký hoạt động (Live Log)", FontAwesomeSolid.TERMINAL, logArea);
        VBox.setVgrow(logCard, Priority.ALWAYS);

        SplitPane split = new SplitPane(requestCard, logCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(metrics, controlCard, split);
        return tab;
    }

    private void startServer() {
        if (server.isRunning()) {
            return;
        }
        int requestedPort;
        try {
            requestedPort = Integer.parseInt(portField.getText().trim());
            if (requestedPort < 1 || requestedPort > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showError("Cổng không hợp lệ", "Hãy nhập số từ 1 đến 65535.");
            return;
        }

        startButton.setDisable(true);
        stopButton.setDisable(true);
        setPill(serverPill, "ĐANG KHỞI ĐỘNG", "pill-warn");
        setPill(dbPill, "DB: ĐANG KIỂM TRA", "pill-warn");
        setDbCard("Đang kiểm tra", "stat-neutral");

        async(() -> {
            String info;
            try {
                database.initialize();
                info = database.describe();
            } catch (DatabaseManager.DatabaseException e) {
                info = "MySQL: " + e.getMessage();
            }
            server.start(requestedPort);
            return info;
        }, info -> {
            dbInfoLabel.setText(info);
            if (info.startsWith("MySQL:")) {
                setPill(dbPill, "DB: LỖI", "pill-bad");
                setDbCard("Lỗi", "stat-bad");
            } else {
                setPill(dbPill, "DB: SẴN SÀNG", "pill-ok");
                setDbCard("Sẵn sàng", "stat-good");
            }
            setPill(serverPill, "ĐANG CHẠY", "pill-ok");
            serverUrlLabel.setText("Web client: " + server.baseUrl()
                    + "/  ·  API: /proxy, /history, /__health");
            portField.setDisable(true);
            startButton.setDisable(true);
            stopButton.setDisable(false);
            openWebButton.setDisable(false);
            appendLog("[SERVER] Sẵn sàng tại " + server.baseUrl() + "/");
            refreshDatabaseView(false);
        }, error -> {
            startButton.setDisable(false);
            stopButton.setDisable(true);
            openWebButton.setDisable(!server.isRunning());
            setPill(serverPill, "LỖI KHỞI ĐỘNG", "pill-bad");
            setDbCard("Lỗi", "stat-bad");
            dbInfoLabel.setText(safeMessage(error));
            appendLog("[LỖI] " + safeMessage(error));
            showError("Không bật được server", safeMessage(error));
        });
    }

    private void stopServer() {
        server.stop();
        setPill(serverPill, "ĐANG TẮT", "pill-neutral");
        startButton.setDisable(false);
        stopButton.setDisable(true);
        openWebButton.setDisable(true);
        portField.setDisable(false);
        serverUrlLabel.setText("Server đã tắt. Bấm “Bật server” để mở lại cổng.");
        appendLog("[SERVER] Đã tắt server.");
    }

    private void openWebClient() {
        if (!server.isRunning()) {
            showInfo("Server chưa chạy", "Hãy bấm “Bật server” trước.");
            return;
        }
        String url = server.baseUrl() + "/";
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } else {
                javafx.scene.input.Clipboard clipboard =
                        javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content =
                        new javafx.scene.input.ClipboardContent();
                content.putString(url);
                clipboard.setContent(content);
                showInfo("Đã copy URL", "Không mở được trình duyệt tự động. URL đã được copy:\n" + url);
            }
        } catch (Exception e) {
            showError("Không mở được trình duyệt", safeMessage(e));
        }
    }

    // ------------------------------------------------------------------
    // Tab 2 — Client Browser
    // ------------------------------------------------------------------

    private VBox buildBrowserTab() {
        VBox tab = new VBox(12);
        tab.getStyleClass().add("tab-content");

        methodBox.getItems().addAll("GET", "POST", "HEAD");
        methodBox.setValue("GET");
        methodBox.getStyleClass().add("field");
        methodBox.setPrefWidth(96);

        urlField.getStyleClass().add("url-input");
        urlField.setPromptText("https://example.com hoặc http://localhost:8080/gioi-thieu");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setOnAction(event -> navigateTo(urlField.getText(), true));

        HBox urlBox = new HBox(8, icon(FontAwesomeSolid.SEARCH, 15, MUTED), urlField);
        urlBox.getStyleClass().add("url-box");
        urlBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(urlBox, Priority.ALWAYS);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        goButton.setText("SEND");
        goButton.setGraphic(icon(FontAwesomeSolid.PAPER_PLANE, 13, WHITE));
        goButton.getStyleClass().add("button-primary");
        goButton.setOnAction(event -> navigateTo(urlField.getText(), true));

        backButton.setText("");
        backButton.setGraphic(icon(FontAwesomeSolid.ARROW_LEFT, 13, DEEP_BLUE));
        backButton.getStyleClass().addAll("button-ghost", "icon-button");
        backButton.setTooltip(new Tooltip("Quay lại"));
        forwardButton.setText("");
        forwardButton.setGraphic(icon(FontAwesomeSolid.ARROW_RIGHT, 13, DEEP_BLUE));
        forwardButton.getStyleClass().addAll("button-ghost", "icon-button");
        forwardButton.setTooltip(new Tooltip("Tiến tới"));
        reloadButton.setText("");
        reloadButton.setGraphic(icon(FontAwesomeSolid.SYNC, 13, DEEP_BLUE));
        reloadButton.getStyleClass().addAll("button-ghost", "icon-button");
        reloadButton.setTooltip(new Tooltip("Tải lại"));
        backButton.setOnAction(event -> goBack());
        forwardButton.setOnAction(event -> goForward());
        reloadButton.setOnAction(event -> executeLoad(currentLoadedUrl.isBlank()
                ? urlField.getText() : currentLoadedUrl));
        backButton.setDisable(true);
        forwardButton.setDisable(true);

        HBox address = new HBox(8, methodBox, urlBox, goButton,
                backButton, forwardButton, reloadButton);
        address.setAlignment(Pos.CENTER_LEFT);

        postBodyField.getStyleClass().add("field");
        postBodyField.setPromptText("Body cho POST, ví dụ: ho_ten=Nguyễn Văn A&ma_sv=24IT277");
        postBodyField.setDisable(true);
        HBox.setHgrow(postBodyField, Priority.ALWAYS);
        methodBox.valueProperty().addListener((obs, old, value) ->
                postBodyField.setDisable(!"POST".equals(value)));
        Label postLabel = new Label("POST body");
        postLabel.setGraphic(icon(FontAwesomeSolid.CODE, 12, MUTED));
        HBox postRow = new HBox(8, postLabel, postBodyField);
        postRow.setAlignment(Pos.CENTER_LEFT);

        HBox badge = infoBadge();
        analysisLabel.getStyleClass().add("analysis");

        VBox toolbar = new VBox(9, address, postRow, badge, analysisLabel);
        toolbar.getStyleClass().add("card");

        webView.setContextMenuEnabled(false);
        webEngine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (suppressLocation || newLocation == null || newLocation.isBlank()) {
                return;
            }
            if (newLocation.startsWith("about:") || newLocation.startsWith("data:")
                    || newLocation.startsWith("javascript:") || newLocation.startsWith("file:")) {
                return;
            }
            if (!newLocation.startsWith("http://") && !newLocation.startsWith("https://")) {
                return;
            }
            if (newLocation.equals(currentLoadedUrl)) {
                return;
            }
            Platform.runLater(() -> navigateTo(newLocation, true));
        });
        plainArea.setEditable(false);
        plainArea.setWrapText(true);
        plainArea.getStyleClass().add("plain-view");
        plainArea.setVisible(false);
        plainArea.setManaged(false);
        StackPane webStack = new StackPane(webView, plainArea);
        webStack.getStyleClass().add("web-frame");
        VBox.setVgrow(webStack, Priority.ALWAYS);
        VBox webCard = new VBox(webStack);
        webCard.getStyleClass().add("web-card");
        VBox.setVgrow(webCard, Priority.ALWAYS);

        TableView<BrowserHit> browserTable = new TableView<>(browserRows);
        browserTable.getStyleClass().add("data-table");
        browserTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        browserTable.setPlaceholder(new Label("Chưa có lượt duyệt nào trong phiên này."));
        browserTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().time(), 92));
        browserTable.getColumns().add(column("URL", FontAwesomeSolid.LINK,
                data -> data.getValue().url(), 420));
        browserTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(data.getValue().ip()), 150));
        browserTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().port()), 74));
        browserTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().status()), 96));
        VBox tableCard = card("Lịch sử phiên duyệt · Time | IP | Port | Status",
                FontAwesomeSolid.HISTORY, browserTable);
        tableCard.setPrefHeight(230);

        SplitPane split = new SplitPane(webCard, tableCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.70);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(toolbar, split);
        return tab;
    }

    private void navigateTo(String rawUrl, boolean pushHistory) {
        String url = normalizeTarget(rawUrl);
        if (url.isBlank()) {
            return;
        }
        if (pushHistory) {
            while (backStack.size() > backIndex + 1) {
                backStack.remove(backStack.size() - 1);
            }
            if (backIndex < 0 || !url.equals(backStack.get(backIndex))) {
                backStack.add(url);
                backIndex = backStack.size() - 1;
            }
        }
        updateNavigationButtons();
        executeLoad(url);
    }

    private void goBack() {
        if (backIndex > 0) {
            backIndex--;
            updateNavigationButtons();
            executeLoad(backStack.get(backIndex));
        }
    }

    private void goForward() {
        if (backIndex >= 0 && backIndex < backStack.size() - 1) {
            backIndex++;
            updateNavigationButtons();
            executeLoad(backStack.get(backIndex));
        }
    }

    private void executeLoad(String rawUrl) {
        String url = normalizeTarget(rawUrl);
        if (url.isBlank()) {
            return;
        }
        String method = methodBox.getValue() == null ? "GET" : methodBox.getValue();
        String body = "POST".equals(method) ? postBodyField.getText() : null;
        int token = ++loadToken;

        urlField.setText(url);
        goButton.setDisable(true);
        setStatusValue(0);
        statusValue.setText("…");
        analysisLabel.setText("Đang tải " + url + " …");

        async(() -> {
            NetworkClient.Response response = NetworkClient.send(url, method,
                    Map.of(), body, null);
            String title = NetworkClient.titleOf(response.body());
            String prepared = response.isHtml()
                    ? NetworkClient.prepareHtml(response.body(), response.finalUrl(), true)
                    : "";
            NetworkClient.Analysis analysis = NetworkClient.analyze(
                    response.isHtml() ? response.body() : "", response.bodyByteLength());
            boolean saved = false;
            try {
                database.recordHistory(url, response.finalUrl(), title,
                        response.statusCode(), response.contentType(),
                        response.bodyByteLength(), response.elapsedMillis(),
                        response.targetIp(), response.targetPort());
                saved = true;
            } catch (DatabaseManager.DatabaseException ignored) {
                // Browser vẫn hoạt động khi MySQL tắt.
            }
            return new BrowserResult(response, prepared, title, analysis, saved);
        }, result -> {
            if (token != loadToken) {
                return;
            }
            goButton.setDisable(false);
            showBrowserResult(result);
        }, error -> {
            if (token != loadToken) {
                return;
            }
            goButton.setDisable(false);
            showBrowserError(url, error);
        });
    }

    private void showBrowserResult(BrowserResult result) {
        NetworkClient.Response response = result.response();
        currentLoadedUrl = response.finalUrl();

        timeValue.setText(response.timeText());
        ipValue.setText(ipv4(response.targetIp()).isBlank() ? "-" : ipv4(response.targetIp()));

        if (result.historySaved()) {
            refreshDatabaseView(false);
        }
        portValue.setText(Integer.toString(response.targetPort()));
        statusValue.setText(statusLabel(response.statusCode()));
        setStatusValue(response.statusCode());
        urlField.setText(response.finalUrl());

        NetworkClient.Analysis analysis = result.analysis();
        analysisLabel.setText("p " + analysis.paragraphs()
                + " · div " + analysis.divs()
                + " · span " + analysis.spans()
                + " · img " + analysis.images()
                + " · a " + analysis.links()
                + " · " + formatBytes(response.bodyByteLength())
                + " · " + response.elapsedMillis() + " ms");

        if (response.isHtml() && !result.preparedHtml().isBlank()) {
            showWeb(result.preparedHtml());
        } else {
            showPlain(response.isHtml() || result.title().isBlank()
                    ? "HTTP " + response.statusCode() + " " + response.reason()
                            + "\n\n" + response.body()
                    : result.title() + "\n\n" + response.body());
        }

        if (backIndex >= 0 && backIndex < backStack.size()) {
            backStack.set(backIndex, response.finalUrl());
        }
        updateNavigationButtons();
        browserRows.add(0, new BrowserHit(response.timeText(), response.targetIp(),
                response.targetPort(), response.statusCode(), response.finalUrl(),
                response.elapsedMillis()));
        while (browserRows.size() > 200) {
            browserRows.remove(browserRows.size() - 1);
        }
        appendLog("[BROWSER] " + response.timeText() + "  " + response.targetIp()
                + ":" + response.targetPort() + "  HTTP " + response.statusCode()
                + "  " + response.finalUrl() + "  (" + response.elapsedMillis() + " ms)");
    }

    private void showBrowserError(String url, Throwable error) {
        timeValue.setText(LocalTime.now().format(TIME_FORMAT));
        ipValue.setText("—");
        portValue.setText("—");
        statusValue.setText("ERR");
        setStatusValue(0);
        analysisLabel.setText("Không tải được URL.");
        showWeb(errorPage("Không tải được URL", sanitize(url) + "\n\n" + safeMessage(error)));
        browserRows.add(0, new BrowserHit(LocalTime.now().format(TIME_FORMAT), "—", 0, 0,
                url, 0));
        while (browserRows.size() > 200) {
            browserRows.remove(browserRows.size() - 1);
        }
        appendLog("[LỖI] " + url + " · " + safeMessage(error));
    }

    private void showWeb(String html) {
        plainArea.setVisible(false);
        plainArea.setManaged(false);
        webView.setVisible(true);
        webView.setManaged(true);
        suppressLocation = true;
        webEngine.loadContent(html, "text/html");
        Platform.runLater(() -> suppressLocation = false);
    }

    private void showPlain(String text) {
        plainArea.setText(text == null ? "" : text);
        plainArea.setVisible(true);
        plainArea.setManaged(true);
        webView.setVisible(false);
        webView.setManaged(false);
    }

    private void showWelcome() {
        String html = """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>
                  :root{color-scheme:light}
                  body{margin:0;background:#f8fafc;color:#0f172a;
                       font-family:'Segoe UI',Arial,sans-serif}
                  .wrap{max-width:760px;margin:48px auto;padding:0 24px}
                  .card{background:#fff;border:1px solid #e2e8f0;border-radius:18px;
                        padding:34px;box-shadow:0 18px 50px rgba(15,23,42,.08)}
                  .badge{display:inline-block;background:#dbeafe;color:#1d4ed8;
                         padding:6px 12px;border-radius:999px;font-size:12px;font-weight:800}
                  h1{font-size:28px;margin:16px 0 10px}p{color:#475569;line-height:1.7}
                  code{background:#f1f5f9;padding:2px 6px;border-radius:6px}
                  .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:22px}
                  .item{border:1px solid #e2e8f0;border-radius:12px;padding:14px 16px}
                  .item b{display:block;margin-bottom:5px;color:#0f172a}
                  .item span{color:#64748b;font-size:13px}
                </style></head><body><div class="wrap"><div class="card">
                <span class="badge">RAW SOCKET · JAVA 21 · JAVAFX</span>
                <h1>Client Browser</h1>
                <p>Nhập URL ở thanh phía trên rồi bấm <b>SEND</b>. Request được gửi bằng
                <code>Socket</code> thuần, phản hồi được parse thủ công và HTML được
                rewrite link thành <code>javascript:loadUrl(...)</code>.</p>
                <div class="grid">
                  <div class="item"><b>GET / POST / HEAD</b><span>Đọc status line, header, chunked, gzip.</span></div>
                  <div class="item"><b>IP &amp; Port thật</b><span>Phân giải bằng InetAddress, hiển thị ngay dưới URL.</span></div>
                  <div class="item"><b>MySQL 3307</b><span>Lịch sử được lưu vào web_history.</span></div>
                  <div class="item"><b>Web client</b><span>Bật server rồi mở http://localhost:8080/.</span></div>
                </div>
                <p style="margin-top:22px">Gợi ý: <code>http://localhost:8080/gioi-thieu</code>
                hoặc <code>https://example.com</code>.</p>
                </div></div></body></html>
                """;
        showWeb(html);
    }

    // ------------------------------------------------------------------
    // Tab 3 — Database
    // ------------------------------------------------------------------

    private VBox buildDatabaseTab() {
        VBox tab = new VBox(14);
        tab.getStyleClass().add("tab-content");

        Button refreshButton = new Button("Làm mới");
        refreshButton.setGraphic(icon(FontAwesomeSolid.SYNC, 13, WHITE));
        refreshButton.getStyleClass().add("button-primary");
        refreshButton.setOnAction(event -> refreshDatabaseView(true));

        Button exportPages = new Button("Export web_data.json");
        exportPages.setGraphic(icon(FontAwesomeSolid.FILE_EXPORT, 13, DEEP_BLUE));
        exportPages.getStyleClass().add("button-ghost");
        exportPages.setOnAction(event -> exportJson(false));

        Button exportHistory = new Button("Export web_history.json");
        exportHistory.setGraphic(icon(FontAwesomeSolid.FILE_DOWNLOAD, 13, DEEP_BLUE));
        exportHistory.getStyleClass().add("button-ghost");
        exportHistory.setOnAction(event -> exportJson(true));

        pagesCountLabel.getStyleClass().add("muted");
        dbNoticeLabel.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(9, refreshButton, exportPages, exportHistory, spacer,
                pagesCountLabel, dbNoticeLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar");
        VBox toolbarCard = new VBox(toolbar);
        toolbarCard.getStyleClass().add("card");

        historyTable.setItems(historyRows);
        historyTable.getStyleClass().add("data-table");
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setPlaceholder(new Label("Chưa có lịch sử trong web_history."));
        historyTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().visitedAt() == null ? ""
                        : data.getValue().visitedAt().format(TIME_FORMAT), 88));
        historyTable.getColumns().add(column("URL", FontAwesomeSolid.LINK,
                data -> data.getValue().finalUrl(), 380));
        historyTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(nullToEmpty(data.getValue().targetIp())), 140));
        historyTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().targetPort()), 72));
        historyTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().statusCode()), 96));
        VBox historyCard = card("Lịch sử trong MySQL · Time | URL | IP | Port | Status",
                FontAwesomeSolid.DATABASE, historyTable);
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        pagesJsonArea.setEditable(false);
        pagesJsonArea.setWrapText(false);
        pagesJsonArea.getStyleClass().add("json-area");
        historyJsonArea.setEditable(false);
        historyJsonArea.setWrapText(false);
        historyJsonArea.getStyleClass().add("json-area");

        Tab pagesTab = new Tab("web_data.json", pagesJsonArea);
        pagesTab.setGraphic(icon(FontAwesomeSolid.FILE_CODE, 12, BLUE));
        Tab historyTab = new Tab("web_history.json", historyJsonArea);
        historyTab.setGraphic(icon(FontAwesomeSolid.HISTORY, 12, BLUE));
        TabPane previews = new TabPane(pagesTab, historyTab);
        previews.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        previews.getStyleClass().add("inner-tabs");
        VBox previewCard = card("File JSON tự sinh", FontAwesomeSolid.FILE_CODE, previews);
        VBox.setVgrow(previewCard, Priority.ALWAYS);

        SplitPane split = new SplitPane(historyCard, previewCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.52);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(toolbarCard, split);
        return tab;
    }

    private void initializeDatabase() {
        async(() -> {
            database.initialize();
            return database.describe();
        }, description -> {
            dbInfoLabel.setText(description);
            setPill(dbPill, "DB: SẴN SÀNG", "pill-ok");
            setDbCard("Sẵn sàng", "stat-good");
            refreshDatabaseView(false);
        }, error -> {
            dbInfoLabel.setText(safeMessage(error));
            setPill(dbPill, "DB: LỖI", "pill-bad");
            setDbCard("Lỗi", "stat-bad");
            dbNoticeLabel.setText("Không kết nối MySQL tại " + database.endpoint());
            appendLog("[DB] " + safeMessage(error));
        });
    }

    private void refreshDatabaseView(boolean notify) {
        async(() -> {
            DatabaseManager.DataSnapshot snapshot = database.refreshData();
            String historyJson = database.exportJson();
            return new DbSnapshot(snapshot.history(), snapshot.pages().size(),
                    snapshot.pagesJson(), historyJson);
        }, snapshot -> {
            historyRows.setAll(snapshot.history());
            pagesCountLabel.setText(snapshot.pageCount() + " trang");
            pagesJsonArea.setText(snapshot.pagesJson());
            historyJsonArea.setText(snapshot.historyJson());
            dbNoticeLabel.setText(snapshot.history().size() + " lịch sử · "
                    + snapshot.pageCount() + " trang");
            if (notify) {
                appendLog("[DB] Đã làm mới dữ liệu MySQL.");
            }
        }, error -> {
            dbNoticeLabel.setText("Lỗi DB: " + safeMessage(error));
            if (notify) {
                showError("Không đọc được database", safeMessage(error));
            }
        });
    }

    private void exportJson(boolean history) {
        async(() -> history ? database.exportHistory() : database.exportPages(),
                path -> {
                    dbNoticeLabel.setText("Đã ghi " + path);
                    appendLog("[DB] Export → " + path);
                    refreshDatabaseView(false);
                },
                error -> showError("Export JSON thất bại", safeMessage(error)));
    }

    // ------------------------------------------------------------------
    // Timer & sự kiện server
    // ------------------------------------------------------------------

    private void startClock() {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            clockLabel.setText(LocalTime.now().format(TIME_FORMAT));
            requestsValue.setText(Long.toString(server.requestCount()));
            activeValue.setText(Integer.toString(server.activeConnections()));
            String uptime = server.isRunning() ? server.uptimeText() : "00:00:00";
            uptimeValue.setText(uptime);
            headerUptimeLabel.setText("Uptime " + uptime);
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void addServerRequest(NetworkServer.RequestInfo info) {
        serverRows.add(0, info);
        while (serverRows.size() > 300) {
            serverRows.remove(serverRows.size() - 1);
        }
        requestsValue.setText(Long.toString(server.requestCount()));
        activeValue.setText(Integer.toString(server.activeConnections()));
        if ("/proxy".equals(info.path())) {
            refreshDatabaseView(false);
        }
    }

    private void appendLog(String message) {
        Runnable task = () -> {
            logArea.appendText(message + System.lineSeparator());
            if (logArea.getLength() > 220_000) {
                logArea.deleteText(0, 60_000);
            }
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void shutdown() {
        server.stop();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Tiện ích UI
    // ------------------------------------------------------------------

    private static FontIcon icon(Ikon code, int size, Color color) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(size);
        icon.setIconColor(color);
        icon.setMouseTransparent(true);
        icon.getStyleClass().add("fa-icon");
        return icon;
    }

    private HBox metricCard(String title, Label value, String styleClass,
            FontIcon icon, String iconStyle) {
        value.getStyleClass().add("metric-value");
        Label caption = new Label(title);
        caption.getStyleClass().add("metric-caption");
        VBox text = new VBox(3, value, caption);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().addAll("metric-icon", iconStyle);

        HBox box = new HBox(14, iconBox, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("metric-card", styleClass);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox wrapper = new HBox(box);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }

    private HBox infoBadge() {
        HBox badge = new HBox();
        badge.getStyleClass().add("info-badge");
        badge.getChildren().addAll(
                badgeBlock("TIME", icon(FontAwesomeSolid.CLOCK, 14, DEEP_BLUE), timeValue),
                badgeSeparator(),
                badgeBlock("IP", icon(FontAwesomeSolid.GLOBE, 14, DEEP_BLUE), ipValue),
                badgeSeparator(),
                badgeBlock("PORT", icon(FontAwesomeSolid.PLUG, 14, DEEP_BLUE), portValue),
                badgeSeparator(),
                badgeBlock("STATUS", statusBadgeIcon, statusValue));
        return badge;
    }

    private VBox badgeBlock(String caption, FontIcon icon, Label value) {
        value.getStyleClass().add("badge-value");
        Label label = new Label(caption);
        label.setGraphic(icon);
        label.getStyleClass().add("badge-label");
        VBox box = new VBox(3, label, value);
        box.getStyleClass().add("badge-block");
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Region badgeSeparator() {
        Region region = new Region();
        region.getStyleClass().add("badge-sep");
        return region;
    }

    private void setDbCard(String text, String styleClass) {
        dbStatusValue.setText(text);
        dbStatusValue.getStyleClass().removeAll("stat-good", "stat-bad", "stat-neutral");
        dbStatusValue.getStyleClass().add(styleClass);

        if ("stat-good".equals(styleClass)) {
            dbCardIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            dbCardIcon.setIconColor(GREEN);
            dbPillIcon.setIconColor(GREEN);
        } else if ("stat-bad".equals(styleClass)) {
            dbCardIcon.setIconCode(FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            dbCardIcon.setIconColor(RED);
            dbPillIcon.setIconColor(RED);
        } else {
            dbCardIcon.setIconCode(FontAwesomeSolid.DATABASE);
            dbCardIcon.setIconColor(VIOLET);
            dbPillIcon.setIconColor(AMBER);
        }
    }

    private static String statusLabel(int status) {
        if (status == 0) {
            return "ERR";
        }
        return switch (status) {
            case 200 -> "200 OK";
            case 201 -> "201 Created";
            case 204 -> "204 No Content";
            case 301 -> "301 Moved";
            case 302 -> "302 Found";
            case 304 -> "304 Not Modified";
            case 400 -> "400 Bad Request";
            case 403 -> "403 Forbidden";
            case 404 -> "404 Not Found";
            case 405 -> "405 Method Not Allowed";
            case 408 -> "408 Timeout";
            case 500 -> "500 Server Error";
            case 502 -> "502 Bad Gateway";
            case 503 -> "503 Unavailable";
            default -> Integer.toString(status);
        };
    }

    private static VBox card(String title, Ikon iconCode, Node content) {
        Label caption = new Label(title);
        caption.setGraphic(icon(iconCode, 13, BLUE));
        caption.getStyleClass().add("card-title");
        VBox box = new VBox(10, caption, content);
        box.getStyleClass().add("card");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static <T> TableColumn<T, String> column(String title, Ikon iconCode,
            java.util.function.Function<TableColumn.CellDataFeatures<T, String>, String> value,
            double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setGraphic(icon(iconCode, 11, MUTED));
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data)));
        column.setPrefWidth(width);
        column.setSortable(false);
        return column;
    }

    private void setPill(Label pill, String text, String styleClass) {
        pill.setText(text);
        pill.getStyleClass().removeAll("pill-ok", "pill-bad", "pill-warn", "pill-neutral");
        pill.getStyleClass().add(styleClass);
    }

    private void setStatusValue(int status) {
        statusValue.getStyleClass().removeAll("stat-good", "stat-bad", "stat-neutral");
        if (status >= 200 && status < 400) {
            statusValue.getStyleClass().add("stat-good");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            statusBadgeIcon.setIconColor(GREEN);
        } else if (status == 0) {
            statusValue.getStyleClass().add("stat-neutral");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            statusBadgeIcon.setIconColor(MUTED);
        } else {
            statusValue.getStyleClass().add("stat-bad");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            statusBadgeIcon.setIconColor(RED);
        }
    }

    private void updateNavigationButtons() {
        backButton.setDisable(backIndex <= 0);
        forwardButton.setDisable(backIndex < 0 || backIndex >= backStack.size() - 1);
    }

    private String normalizeTarget(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("/")) {
            int port = server.isRunning() ? server.port() : NetworkServer.DEFAULT_PORT;
            return "http://localhost:" + port + value;
        }
        return NetworkClient.normalizeUrl(value);
    }

    private <T> void async(Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        workers.submit(() -> {
            try {
                T value = task.call();
                Platform.runLater(() -> success.accept(value));
            } catch (Throwable error) {
                Platform.runLater(() -> failure.accept(error));
            }
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl);
        }
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl);
        }
        alert.showAndWait();
    }

    private static String errorPage(String title, String message) {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>
                  :root{color-scheme:light}
                  body{margin:0;background:#fef2f2;color:#7f1d1d;
                       font-family:'Segoe UI',Arial,sans-serif}
                  .wrap{max-width:720px;margin:48px auto;padding:0 24px}
                  .card{background:#fff;border:1px solid #fecaca;border-radius:16px;
                        padding:30px;box-shadow:0 16px 40px rgba(127,29,29,.08)}
                  h1{margin:0 0 12px;font-size:24px}
                  pre{white-space:pre-wrap;word-break:break-word;background:#fff7f7;
                      border:1px solid #fee2e2;border-radius:10px;padding:16px;
                      color:#991b1b;font-family:Consolas,monospace;font-size:13px}
                  code{background:#fee2e2;padding:2px 6px;border-radius:6px}
                </style></head><body><div class="wrap"><div class="card">
                <h1>%s</h1><pre>%s</pre>
                <p style="color:#7f1d1d">Kiểm tra URL, kết nối mạng hoặc bật server tại tab
                <code>Server</code>.</p>
                </div></div></body></html>
                """.formatted(sanitize(title), sanitize(message));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "không rõ lỗi";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String ipv4(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String ip = value.trim();
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        if (ip.contains(".") && !ip.contains(":")) {
            return ip;
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(ip)) {
                if (address instanceof Inet4Address) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return ip;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

```

## `src/ui/style.css`

_Theme trắng/xanh, card, badge, bảng và icon_

```css
/* =====================================================================
   Lab 7 - Enterprise Dashboard Light Theme
   Tone xanh duong, nen sang, khoi ro rang, card trang, shadow nhe.
   ===================================================================== */

.root {
    -fx-font-family: "Segoe UI", Arial, sans-serif;
    -fx-font-size: 13px;
    -fx-background-color: #f4f6f9;
    -fx-text-fill: #333333;
}

.app-root {
    -fx-background-color: #f4f6f9;
}

/* ------------------------------- Header ------------------------------- */

.app-header {
    -fx-background-color: #007bff;
    -fx-padding: 13 24 13 24;
    -fx-border-color: #0056b3 transparent transparent transparent;
    -fx-border-width: 0;
    -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.18), 18, 0.12, 0, 4);
}

.brand-mark {
    -fx-alignment: center;
    -fx-min-width: 46;
    -fx-min-height: 46;
    -fx-pref-width: 46;
    -fx-pref-height: 46;
    -fx-background-color: rgba(255, 255, 255, 0.18);
    -fx-background-radius: 12;
    -fx-padding: 8;
    -fx-border-color: rgba(255, 255, 255, 0.38);
    -fx-border-width: 1;
    -fx-border-radius: 12;
}

.brand-title {
    -fx-font-size: 18px;
    -fx-font-weight: 900;
    -fx-text-fill: #ffffff;
}

.brand-subtitle {
    -fx-font-size: 11px;
    -fx-font-weight: 600;
    -fx-text-fill: rgba(255, 255, 255, 0.82);
}

.clock {
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 12px;
    -fx-font-weight: 700;
    -fx-text-fill: #ffffff;
    -fx-background-color: rgba(255, 255, 255, 0.16);
    -fx-background-radius: 8;
    -fx-padding: 7 12 7 12;
    -fx-border-color: rgba(255, 255, 255, 0.28);
    -fx-border-width: 1;
    -fx-border-radius: 8;
    -fx-graphic-text-gap: 6;
}

.pill {
    -fx-padding: 6 12 6 12;
    -fx-background-radius: 999;
    -fx-font-size: 10.5px;
    -fx-font-weight: 800;
}

.pill-ok {
    -fx-background-color: #d4edda;
    -fx-text-fill: #155724;
}

.pill-bad {
    -fx-background-color: #f8d7da;
    -fx-text-fill: #721c24;
}

.pill-warn {
    -fx-background-color: #fff3cd;
    -fx-text-fill: #856404;
}

.pill-neutral {
    -fx-background-color: #e2e3e5;
    -fx-text-fill: #383d41;
}

/* ------------------------------- Tabs ------------------------------- */

.main-tabs {
    -fx-background-color: transparent;
    -fx-tab-min-height: 40px;
    -fx-tab-max-height: 40px;
}

.main-tabs > .tab-header-area {
    -fx-padding: 10 20 0 20;
}

.main-tabs > .tab-header-area > .tab-header-background {
    -fx-background-color: transparent;
}

.main-tabs .tab {
    -fx-background-color: #e9ecef;
    -fx-background-radius: 10 10 0 0;
    -fx-padding: 9 22 9 22;
    -fx-border-color: transparent;
}

.main-tabs .tab:hover {
    -fx-background-color: #dde2e6;
}

.main-tabs .tab:selected {
    -fx-background-color: #ffffff;
    -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.05), 10, 0.15, 0, 1);
}

.main-tabs .tab .tab-label {
    -fx-text-fill: #495057;
    -fx-font-size: 13px;
    -fx-font-weight: 700;
}

.main-tabs .tab:selected .tab-label {
    -fx-text-fill: #0056b3;
}

.main-tabs > .tab-content-area {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.tab-content {
    -fx-padding: 16 20 20 20;
    -fx-background-color: transparent;
}

/* ------------------------------- Cards ------------------------------- */

.card {
    -fx-background-color: #ffffff;
    -fx-background-radius: 10;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-border-width: 1;
    -fx-padding: 16;
    -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.07), 16, 0.12, 0, 4);
}

.card-title {
    -fx-font-size: 13px;
    -fx-font-weight: 800;
    -fx-text-fill: #343a40;
}

.toolbar {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.muted {
    -fx-font-size: 12px;
    -fx-text-fill: #6c757d;
}

.analysis {
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 11.5px;
    -fx-text-fill: #6c757d;
    -fx-background-color: #f8f9fa;
    -fx-background-radius: 8;
    -fx-padding: 7 10 7 10;
    -fx-border-color: #dee2e6;
    -fx-border-width: 1;
    -fx-border-radius: 8;
}

/* ------------------------------- Buttons ------------------------------- */

.button {
    -fx-cursor: hand;
    -fx-font-weight: 700;
    -fx-font-size: 12px;
    -fx-background-radius: 8;
    -fx-border-radius: 8;
    -fx-padding: 9 15 9 15;
}

.button-primary {
    -fx-background-color: #007bff;
    -fx-text-fill: white;
}

.button-primary:hover {
    -fx-background-color: #0069d9;
}

.button-danger {
    -fx-background-color: #dc3545;
    -fx-text-fill: white;
}

.button-danger:hover {
    -fx-background-color: #c82333;
}

.button-ghost {
    -fx-background-color: #ffffff;
    -fx-text-fill: #343a40;
    -fx-border-color: #ced4da;
    -fx-border-width: 1;
}

.button-ghost:hover {
    -fx-background-color: #f8f9fa;
    -fx-border-color: #007bff;
    -fx-text-fill: #007bff;
}

.button:disabled {
    -fx-opacity: 0.5;
    -fx-cursor: default;
}

/* ------------------------------- Inputs ------------------------------- */

.field {
    -fx-background-color: #ffffff;
    -fx-text-fill: #333333;
    -fx-prompt-text-fill: #adb5bd;
    -fx-border-color: #ced4da;
    -fx-border-width: 1;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
    -fx-padding: 9 12 9 12;
    -fx-font-size: 12.5px;
}

.field:focused {
    -fx-border-color: #80bdff;
    -fx-effect: dropshadow(gaussian, rgba(0, 123, 255, 0.18), 6, 0.2, 0, 0);
}

.combo-box.field {
    -fx-padding: 0;
}

.combo-box.field .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #333333;
    -fx-padding: 9 10 9 10;
    -fx-font-size: 12.5px;
}

.combo-box.field .arrow-button {
    -fx-background-color: transparent;
}

.combo-box.field .arrow {
    -fx-background-color: #6c757d;
}

.combo-box-popup .list-view {
    -fx-background-color: #ffffff;
    -fx-border-color: #ced4da;
    -fx-border-radius: 8;
    -fx-background-radius: 8;
}

.combo-box-popup .list-cell {
    -fx-background-color: #ffffff;
    -fx-text-fill: #333333;
    -fx-padding: 8 12 8 12;
}

.combo-box-popup .list-cell:hover,
.combo-box-popup .list-cell:selected {
    -fx-background-color: #e7f1ff;
    -fx-text-fill: #0056b3;
}

/* Ô URL có icon Search bên trong */
.url-box {
    -fx-background-color: #ffffff;
    -fx-background-radius: 8;
    -fx-border-color: #ced4da;
    -fx-border-width: 1;
    -fx-border-radius: 8;
    -fx-padding: 0 10 0 11;
    -fx-min-height: 36;
    -fx-pref-height: 36;
}

.url-box:hover {
    -fx-border-color: #80bdff;
}

.url-input {
    -fx-background-color: transparent;
    -fx-text-fill: #212529;
    -fx-prompt-text-fill: #adb5bd;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 8 0 8 0;
    -fx-font-size: 12.5px;
}

.url-input:focused {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-effect: null;
}

.icon-button {
    -fx-min-width: 34;
    -fx-pref-width: 34;
    -fx-min-height: 34;
    -fx-pref-height: 34;
    -fx-padding: 7 9 7 9;
    -fx-alignment: center;
}

/* ------------------------------- Stat cards ------------------------------- */

.metric-card {
    -fx-padding: 16 18 16 18;
    -fx-background-color: #ffffff;
    -fx-background-radius: 10;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-border-width: 1;
    -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.06), 14, 0.12, 0, 3);
}

.metric-blue {
    -fx-border-color: #b8daff;
}

.metric-green {
    -fx-border-color: #c3e6cb;
}

.metric-amber {
    -fx-border-color: #ffeeba;
}

.metric-violet {
    -fx-border-color: #d6d8db;
}

.metric-value {
    -fx-font-size: 22px;
    -fx-font-weight: 900;
    -fx-text-fill: #0056b3;
}

.metric-caption {
    -fx-font-size: 11px;
    -fx-font-weight: 700;
    -fx-text-fill: #6c757d;
}

.metric-icon {
    -fx-alignment: center;
    -fx-min-width: 56;
    -fx-min-height: 56;
    -fx-pref-width: 56;
    -fx-pref-height: 56;
    -fx-background-radius: 13;
    -fx-border-width: 1;
    -fx-border-radius: 13;
}

.metric-icon-blue {
    -fx-background-color: #eaf3ff;
    -fx-border-color: #b8daff;
}

.metric-icon-green {
    -fx-background-color: #eaf8ee;
    -fx-border-color: #c3e6cb;
}

.metric-icon-violet {
    -fx-background-color: #f2edff;
    -fx-border-color: #d8ccf5;
}

.metric-icon-amber {
    -fx-background-color: #fff5e6;
    -fx-border-color: #ffe0b2;
}

.stat-card {
    -fx-padding: 12 16 12 16;
    -fx-background-color: #ffffff;
    -fx-background-radius: 10;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-border-width: 1;
}

.stat-blue {
    -fx-border-color: #b8daff;
}

.stat-violet {
    -fx-border-color: #d6d8db;
}

.stat-green {
    -fx-border-color: #c3e6cb;
}

.stat-amber {
    -fx-border-color: #ffeeba;
}

.stat-caption {
    -fx-font-size: 9.5px;
    -fx-font-weight: 900;
    -fx-text-fill: #6c757d;
}

.stat-value {
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 18px;
    -fx-font-weight: 800;
    -fx-text-fill: #333333;
}

.stat-good {
    -fx-text-fill: #28a745;
}

.stat-bad {
    -fx-text-fill: #dc3545;
}

.stat-neutral {
    -fx-text-fill: #6c757d;
}

/* ------------------------------- Tables ------------------------------- */

.data-table {
    -fx-background-color: #ffffff;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-padding: 0;
    -fx-table-cell-border-color: transparent;
}

.data-table .column-header-background {
    -fx-background-color: #f8f9fa;
    -fx-background-radius: 10 10 0 0;
}

.data-table .column-header,
.data-table .filler {
    -fx-background-color: transparent;
    -fx-border-color: transparent transparent #dee2e6 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-size: 36px;
}

.data-table .column-header .label {
    -fx-text-fill: #495057;
    -fx-font-size: 11px;
    -fx-font-weight: 800;
    -fx-padding: 0 0 0 8;
    -fx-graphic-text-gap: 6;
}

.data-table .table-row-cell {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #f1f3f5 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 0;
    -fx-cell-size: 36px;
}

.data-table .table-row-cell:odd {
    -fx-background-color: #f8f9fa;
}

.data-table .table-row-cell:selected {
    -fx-background-color: #cce5ff;
    -fx-text-background-color: #212529;
}

.data-table .table-cell {
    -fx-text-fill: #343a40;
    -fx-font-size: 12px;
    -fx-padding: 7 10 7 10;
    -fx-border-color: transparent;
}

.data-table .placeholder .label {
    -fx-text-fill: #adb5bd;
    -fx-font-size: 12px;
}

/* ------------------------------- Log / JSON / plain ------------------------------- */

.log-area,
.json-area,
.plain-view {
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 12px;
    -fx-text-fill: #212529;
    -fx-control-inner-background: #f8f9fa;
    -fx-background-color: #f8f9fa;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-padding: 2;
}

.log-area .content,
.json-area .content,
.plain-view .content {
    -fx-background-color: #f8f9fa;
}

.plain-view {
    -fx-control-inner-background: #ffffff;
    -fx-background-color: #ffffff;
}

/* ------------------------------- Web frame ------------------------------- */

.web-card {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.web-frame {
    -fx-background-color: #ffffff;
    -fx-border-color: #dee2e6;
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-padding: 1;
}

/* ------------------------------- Inner tabs ------------------------------- */

.inner-tabs {
    -fx-background-color: transparent;
}

.inner-tabs > .tab-header-area {
    -fx-padding: 0 0 6 0;
}

.inner-tabs > .tab-header-area > .tab-header-background {
    -fx-background-color: transparent;
}

.inner-tabs .tab {
    -fx-background-color: #e9ecef;
    -fx-background-radius: 8 8 0 0;
    -fx-padding: 6 14 6 14;
}

.inner-tabs .tab:selected {
    -fx-background-color: #cce5ff;
}

.inner-tabs .tab .tab-label {
    -fx-text-fill: #495057;
    -fx-font-size: 11px;
    -fx-font-weight: 800;
}

.inner-tabs .tab:selected .tab-label {
    -fx-text-fill: #0056b3;
}

.inner-tabs > .tab-content-area {
    -fx-background-color: transparent;
}

/* ------------------------------- SplitPane / ScrollBar / Tooltip ------------------------------- */

.split-pane {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.split-pane > .split-pane-divider {
    -fx-background-color: #dee2e6;
    -fx-padding: 0;
    -fx-pref-height: 6;
}

.scroll-bar:vertical,
.scroll-bar:horizontal {
    -fx-background-color: transparent;
}

.scroll-bar .thumb {
    -fx-background-color: #ced4da;
    -fx-background-radius: 999;
}

.scroll-bar .thumb:hover {
    -fx-background-color: #adb5bd;
}

.scroll-bar .increment-button,
.scroll-bar .decrement-button {
    -fx-background-color: transparent;
    -fx-padding: 0;
}

.tooltip {
    -fx-background-color: #343a40;
    -fx-text-fill: #ffffff;
    -fx-background-radius: 8;
    -fx-font-size: 11px;
    -fx-padding: 7 10 7 10;
}

/* ------------------------------- Alerts ------------------------------- */

.dialog-pane {
    -fx-background-color: #ffffff;
}

.dialog-pane .header-panel {
    -fx-background-color: #f8f9fa;
}

.dialog-pane .header-panel .label {
    -fx-text-fill: #212529;
    -fx-font-weight: 800;
}

.dialog-pane .content.label {
    -fx-text-fill: #495057;
}


/* =====================================================================
   Bổ sung giao diện Enterprise Dashboard (header, badge, nút)
   ===================================================================== */

.header-label {
    -fx-text-fill: rgba(255, 255, 255, 0.86);
    -fx-font-size: 11px;
    -fx-font-weight: 700;
    -fx-graphic-text-gap: 5;
}

.header-uptime {
    -fx-font-size: 11px;
}

.header-port {
    -fx-background-color: #ffffff;
    -fx-text-fill: #0056b3;
    -fx-border-color: #ffffff;
    -fx-pref-height: 32px;
    -fx-padding: 5 8 5 8;
    -fx-font-size: 12px;
}

.header-port:focused {
    -fx-border-color: #80bdff;
}

.app-header .button {
    -fx-padding: 7 12 7 12;
}

.app-header .button-ghost {
    -fx-background-color: rgba(255, 255, 255, 0.16);
    -fx-text-fill: #ffffff;
    -fx-border-color: rgba(255, 255, 255, 0.48);
    -fx-border-width: 1;
}

.app-header .button-ghost:hover {
    -fx-background-color: rgba(255, 255, 255, 0.24);
    -fx-text-fill: #ffffff;
    -fx-border-color: rgba(255, 255, 255, 0.85);
}

.pill {
    -fx-graphic-text-gap: 6;
}

.fa-icon {
    -fx-opacity: 1;
}

.button-success {
    -fx-background-color: #28a745;
    -fx-text-fill: #ffffff;
}

.button-success:hover {
    -fx-background-color: #218838;
}

.metric-value.stat-good {
    -fx-text-fill: #28a745;
}

.metric-value.stat-bad {
    -fx-text-fill: #dc3545;
}

.metric-value.stat-neutral {
    -fx-text-fill: #6c757d;
}

/* Dải badge Time | IP | Port | Status dưới thanh URL */
.info-badge {
    -fx-background-color: linear-gradient(to right, #eef6ff, #f7fbff);
    -fx-background-radius: 10;
    -fx-border-color: #b8daff;
    -fx-border-width: 1;
    -fx-border-radius: 10;
    -fx-padding: 10 4 10 4;
}

.badge-block {
    -fx-padding: 0 16 0 16;
}

.badge-label {
    -fx-font-size: 9.5px;
    -fx-font-weight: 900;
    -fx-text-fill: #6c757d;
    -fx-graphic-text-gap: 5;
}

.badge-value {
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 15px;
    -fx-font-weight: 800;
    -fx-text-fill: #0056b3;
}

.badge-value.stat-good {
    -fx-text-fill: #28a745;
}

.badge-value.stat-bad {
    -fx-text-fill: #dc3545;
}

.badge-value.stat-neutral {
    -fx-text-fill: #6c757d;
}

.badge-sep {
    -fx-background-color: #b8daff;
    -fx-min-width: 1;
    -fx-pref-width: 1;
    -fx-max-width: 1;
    -fx-min-height: 30;
    -fx-pref-height: 30;
}

/* Làm nổi bật tiêu đề các khu vực */
.card-title {
    -fx-font-size: 13px;
    -fx-font-weight: 900;
    -fx-text-fill: #343a40;
    -fx-graphic-text-gap: 7;
}

.data-table .table-row-cell:selected .table-cell {
    -fx-text-fill: #212529;
}
```

## `web/index.html`

_Web Client Enterprise_

```html
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HTTP Server &amp; Client Dashboard · Lab 7</title>
<style>
  :root {
    --ink: #0f172a;
    --muted: #64748b;
    --line: rgba(148, 163, 184, .28);
    --blue: #2563eb;
    --blue-2: #3b82f6;
    --violet: #7c3aed;
    --green: #059669;
    --amber: #d97706;
    --red: #dc2626;
    --glass: rgba(255, 255, 255, .66);
    --glass-strong: rgba(255, 255, 255, .86);
  }

  * { box-sizing: border-box; }

  html, body {
    height: 100%;
    margin: 0;
  }

  body {
    font-family: "Segoe UI", Inter, Arial, sans-serif;
    color: var(--ink);
    background:
      radial-gradient(900px 600px at 8% -10%, rgba(147, 197, 253, .55), transparent 60%),
      radial-gradient(800px 600px at 95% 5%, rgba(196, 181, 253, .48), transparent 62%),
      radial-gradient(900px 700px at 45% 110%, rgba(167, 243, 208, .38), transparent 65%),
      linear-gradient(135deg, #f8fbff 0%, #eef5fc 45%, #f8fafc 100%);
    background-attachment: fixed;
    overflow: hidden;
  }

  body::before,
  body::after {
    content: "";
    position: fixed;
    z-index: -1;
    border-radius: 999px;
    filter: blur(10px);
    opacity: .55;
    pointer-events: none;
  }

  body::before {
    width: 300px;
    height: 300px;
    left: -100px;
    top: 38%;
    background: radial-gradient(circle at 35% 35%, rgba(96, 165, 250, .55), rgba(191, 219, 254, .12));
  }

  body::after {
    width: 360px;
    height: 360px;
    right: -120px;
    bottom: -80px;
    background: radial-gradient(circle at 40% 40%, rgba(167, 139, 250, .45), rgba(237, 233, 254, .08));
  }

  button, input { font: inherit; }

  .app {
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: 18px 22px 20px;
    gap: 14px;
  }

  /* ------------------------------- header ------------------------------- */

  .topbar {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 4px 6px 2px;
    flex: 0 0 auto;
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  }

  .logo {
    width: 46px;
    height: 46px;
    display: grid;
    place-items: center;
    border-radius: 14px;
    color: #fff;
    font-weight: 900;
    font-size: 18px;
    letter-spacing: -.5px;
    background: linear-gradient(145deg, #60a5fa, #2563eb 60%, #1e40af);
    box-shadow: 0 12px 26px rgba(37, 99, 235, .30), inset 0 1px 0 rgba(255, 255, 255, .35);
  }

  .brand h1 {
    margin: 0;
    font-size: 18px;
    letter-spacing: -.3px;
    font-weight: 800;
  }

  .brand p {
    margin: 2px 0 0;
    color: var(--muted);
    font-size: 11.5px;
    font-weight: 600;
  }

  .spacer { flex: 1; }

  .tabs {
    display: flex;
    gap: 4px;
    padding: 4px;
    border-radius: 13px;
    background: rgba(255, 255, 255, .48);
    border: 1px solid rgba(255, 255, 255, .8);
    box-shadow: 0 8px 24px rgba(15, 23, 42, .05);
    backdrop-filter: blur(16px);
  }

  .tab-btn {
    border: 0;
    background: transparent;
    color: var(--muted);
    padding: 9px 16px;
    border-radius: 9px;
    font-size: 12.5px;
    font-weight: 800;
    cursor: pointer;
    transition: .18s ease;
  }

  .tab-btn:hover { color: var(--ink); background: rgba(255, 255, 255, .55); }

  .tab-btn.active {
    color: #1d4ed8;
    background: #fff;
    box-shadow: 0 5px 16px rgba(37, 99, 235, .14);
  }

  .live-pill {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 7px 12px;
    border-radius: 999px;
    background: rgba(220, 252, 231, .72);
    color: #15803d;
    font-size: 11px;
    font-weight: 800;
    border: 1px solid rgba(187, 247, 208, .9);
    white-space: nowrap;
  }

  .live-pill .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #22c55e;
    box-shadow: 0 0 0 4px rgba(34, 197, 94, .14);
  }

  /* ------------------------------- layout ------------------------------- */

  .shell {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .panel {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .panel[hidden] { display: none !important; }

  .glass {
    background: var(--glass);
    border: 1px solid rgba(255, 255, 255, .82);
    border-radius: 20px;
    box-shadow:
      0 18px 50px rgba(15, 23, 42, .075),
      inset 0 1px 0 rgba(255, 255, 255, .85);
    backdrop-filter: blur(22px) saturate(135%);
    -webkit-backdrop-filter: blur(22px) saturate(135%);
  }

  /* ------------------------------- address ------------------------------- */

  .address-card {
    padding: 14px 16px 15px;
    flex: 0 0 auto;
  }

  .nav-row {
    display: flex;
    align-items: center;
    gap: 9px;
  }

  .icon-btn {
    width: 38px;
    height: 38px;
    flex: 0 0 auto;
    display: grid;
    place-items: center;
    border-radius: 11px;
    border: 1px solid rgba(203, 213, 225, .78);
    background: rgba(255, 255, 255, .76);
    color: #475569;
    cursor: pointer;
    font-size: 17px;
    font-weight: 700;
    transition: .16s ease;
  }

  .icon-btn:hover:not(:disabled) {
    border-color: #bfdbfe;
    background: #fff;
    color: var(--blue);
    transform: translateY(-1px);
  }

  .icon-btn:disabled { opacity: .38; cursor: default; }

  .url-box {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 10px;
    height: 42px;
    padding: 0 14px;
    border-radius: 12px;
    background: rgba(248, 250, 252, .82);
    border: 1px solid rgba(203, 213, 225, .72);
    transition: .16s ease;
  }

  .url-box:focus-within {
    background: #fff;
    border-color: #93c5fd;
    box-shadow: 0 0 0 4px rgba(59, 130, 246, .10);
  }

  .url-box .lock {
    color: #94a3b8;
    font-size: 14px;
  }

  #urlInput {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--ink);
    font-size: 13.5px;
    font-weight: 600;
  }

  #urlInput::placeholder { color: #94a3b8; font-weight: 500; }

  .go-btn {
    height: 42px;
    padding: 0 24px;
    border: 0;
    border-radius: 12px;
    color: #fff;
    font-weight: 800;
    font-size: 12.5px;
    letter-spacing: .7px;
    cursor: pointer;
    background: linear-gradient(135deg, #3b82f6, #2563eb 55%, #1d4ed8);
    box-shadow: 0 10px 22px rgba(37, 99, 235, .28), inset 0 1px 0 rgba(255, 255, 255, .28);
    transition: .16s ease;
  }

  .go-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 13px 28px rgba(37, 99, 235, .34); }
  .go-btn:disabled { opacity: .55; cursor: wait; }

  .spin {
    display: inline-block;
    width: 13px;
    height: 13px;
    border: 2px solid rgba(255, 255, 255, .45);
    border-top-color: #fff;
    border-radius: 50%;
    animation: spin .7s linear infinite;
    vertical-align: -2px;
    margin-right: 6px;
  }

  @keyframes spin { to { transform: rotate(360deg); } }

  /* ------------------------------- stats ------------------------------- */

  .stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 10px;
    margin-top: 12px;
  }

  .stat {
    --accent: #2563eb;
    position: relative;
    overflow: hidden;
    padding: 11px 14px 12px;
    border-radius: 14px;
    background: var(--glass-strong);
    border: 1px solid rgba(226, 232, 240, .8);
    min-width: 0;
  }

  .stat::before {
    content: "";
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    background: var(--accent);
    opacity: .85;
  }

  .stat .label {
    color: var(--muted);
    font-size: 9.5px;
    font-weight: 900;
    letter-spacing: 1.1px;
  }

  .stat .value {
    margin-top: 3px;
    font-family: Consolas, "Cascadia Mono", monospace;
    font-size: 18px;
    font-weight: 800;
    color: var(--ink);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .stat.time { --accent: #3b82f6; }
  .stat.ip { --accent: #8b5cf6; }
  .stat.ip .value { font-size: 15px; }
  .stat.port { --accent: #10b981; }
  .stat.status { --accent: #f59e0b; }
  .stat.status .value.good { color: #15803d; }
  .stat.status .value.warn { color: #b45309; }
  .stat.status .value.bad { color: #b91c1c; }
  .stat.status .value.neutral { color: #64748b; }

  .meta-row {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
    margin-top: 10px;
    min-height: 22px;
  }

  .chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 9px;
    border-radius: 8px;
    background: rgba(241, 245, 249, .85);
    border: 1px solid rgba(226, 232, 240, .8);
    color: #64748b;
    font-family: Consolas, monospace;
    font-size: 10.5px;
    font-weight: 700;
  }

  .chip b { color: #334155; }

  .meta-title {
    color: #475569;
    font-size: 12px;
    font-weight: 700;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 48%;
  }

  .meta-note {
    margin-left: auto;
    color: #94a3b8;
    font-size: 11px;
    white-space: nowrap;
  }

  /* ------------------------------- stage ------------------------------- */

  .browser-stage {
    flex: 1;
    min-height: 0;
    overflow: hidden;
    position: relative;
    background: rgba(255, 255, 255, .78);
  }

  .browser-stage::before {
    content: "";
    position: absolute;
    inset: 0 0 auto 0;
    height: 34px;
    background: linear-gradient(to bottom, rgba(248, 250, 252, .9), rgba(248, 250, 252, 0));
    pointer-events: none;
    z-index: 1;
  }

  #frame {
    width: 100%;
    height: 100%;
    border: 0;
    background: #fff;
    display: block;
  }

  .empty-state {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    padding: 32px;
    text-align: center;
    color: var(--muted);
    background:
      radial-gradient(500px 300px at 50% 20%, rgba(219, 234, 254, .7), transparent 70%),
      rgba(255, 255, 255, .82);
  }

  .empty-state[hidden] { display: none !important; }

  .empty-state .inner { max-width: 440px; }
  .empty-state .big {
    width: 62px;
    height: 62px;
    margin: 0 auto 16px;
    display: grid;
    place-items: center;
    border-radius: 20px;
    background: linear-gradient(145deg, #eff6ff, #dbeafe);
    border: 1px solid #bfdbfe;
    font-size: 28px;
  }
  .empty-state h3 { margin: 0 0 7px; color: var(--ink); font-size: 17px; }
  .empty-state p { margin: 0; line-height: 1.65; font-size: 12.5px; }
  .empty-state code {
    background: #f1f5f9;
    border: 1px solid #e2e8f0;
    border-radius: 6px;
    padding: 2px 6px;
    color: #334155;
  }

  /* ------------------------------- history ------------------------------- */

  .history-card {
    flex: 1;
    min-height: 0;
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .history-head {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .history-head h2 { margin: 0; font-size: 16px; letter-spacing: -.2px; }
  .history-head p { margin: 3px 0 0; color: var(--muted); font-size: 11.5px; }
  .history-head .spacer { flex: 1; }

  .refresh-btn {
    border: 1px solid rgba(203, 213, 225, .8);
    background: rgba(255, 255, 255, .8);
    color: #334155;
    border-radius: 10px;
    padding: 8px 13px;
    font-size: 12px;
    font-weight: 800;
    cursor: pointer;
  }

  .refresh-btn:hover { background: #fff; border-color: #bfdbfe; color: var(--blue); }

  .table-wrap {
    flex: 1;
    min-height: 0;
    overflow: auto;
    border-radius: 14px;
    border: 1px solid rgba(226, 232, 240, .9);
    background: rgba(255, 255, 255, .7);
  }

  table {
    width: 100%;
    border-collapse: separate;
    border-spacing: 0;
    font-size: 12px;
  }

  thead th {
    position: sticky;
    top: 0;
    z-index: 2;
    text-align: left;
    padding: 11px 13px;
    color: #64748b;
    font-size: 9.5px;
    font-weight: 900;
    letter-spacing: .8px;
    text-transform: uppercase;
    background: rgba(248, 250, 252, .96);
    border-bottom: 1px solid #e2e8f0;
    backdrop-filter: blur(10px);
  }

  tbody td {
    padding: 11px 13px;
    border-bottom: 1px solid #f1f5f9;
    color: #334155;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 340px;
  }

  tbody tr { cursor: pointer; transition: .12s ease; }
  tbody tr:hover { background: rgba(219, 234, 254, .45); }
  tbody tr:last-child td { border-bottom: 0; }

  td.mono { font-family: Consolas, monospace; font-size: 11.5px; }
  td.url-cell { max-width: 420px; }
  td.title-cell { max-width: 200px; }

  .status-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 46px;
    padding: 3px 8px;
    border-radius: 999px;
    font-family: Consolas, monospace;
    font-size: 10.5px;
    font-weight: 800;
    background: #f1f5f9;
    color: #475569;
  }

  .status-badge.good { background: #dcfce7; color: #15803d; }
  .status-badge.info { background: #dbeafe; color: #1d4ed8; }
  .status-badge.warn { background: #fef3c7; color: #b45309; }
  .status-badge.bad { background: #fee2e2; color: #b91c1c; }

  .table-empty {
    padding: 42px 20px;
    text-align: center;
    color: #94a3b8;
    font-size: 12.5px;
  }

  .toast {
    position: fixed;
    left: 50%;
    bottom: 24px;
    transform: translate(-50%, 20px);
    max-width: min(680px, 90vw);
    padding: 11px 16px;
    border-radius: 12px;
    background: rgba(15, 23, 42, .92);
    color: #fff;
    font-size: 12px;
    font-weight: 600;
    box-shadow: 0 16px 40px rgba(15, 23, 42, .28);
    opacity: 0;
    pointer-events: none;
    transition: .22s ease;
    z-index: 50;
  }

  .toast.show { opacity: 1; transform: translate(-50%, 0); }

  @media (max-width: 760px) {
    .app { padding: 12px; }
    .brand p { display: none; }
    .live-pill { display: none; }
    .stats { grid-template-columns: 1fr 1fr; }
    .meta-title { max-width: 100%; }
    .meta-note { display: none; }
    .tab-btn { padding: 8px 11px; }
    .go-btn { padding: 0 16px; }
  }

  /* =====================================================================
     ENTERPRISE DASHBOARD LIGHT THEME
     ===================================================================== */
  :root {
    --ink: #333333;
    --muted: #6c757d;
    --line: #dee2e6;
    --blue: #0056b3;
    --blue-2: #007bff;
    --violet: #6f42c1;
    --green: #28a745;
    --amber: #fd7e14;
    --red: #dc3545;
    --glass: #ffffff;
    --glass-strong: #ffffff;
  }

  body {
    color: var(--ink);
    background: #f4f6f9;
    background-image: none;
    overflow: hidden;
  }

  body::before, body::after { display: none; }

  .app { padding: 16px 20px 20px; gap: 14px; }

  .topbar {
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    padding: 12px 16px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, .06);
  }

  .logo {
    width: 42px;
    height: 42px;
    border-radius: 10px;
    background: linear-gradient(135deg, #0056b3, #007bff);
    box-shadow: 0 4px 12px rgba(0, 86, 179, .24);
  }

  .brand h1 { color: #0056b3; font-size: 18px; }
  .brand p { color: #6c757d; }

  .tabs {
    background: #f1f3f5;
    border: 1px solid var(--line);
    border-radius: 8px;
    box-shadow: none;
    backdrop-filter: none;
  }

  .tab-btn { color: #6c757d; border-radius: 6px; }
  .tab-btn:hover { color: #0056b3; background: #e9ecef; }
  .tab-btn.active { color: #0056b3; background: #ffffff; box-shadow: 0 1px 4px rgba(0, 0, 0, .08); }

  .live-pill {
    background: #d4edda;
    color: #155724;
    border-color: #c3e6cb;
  }
  .live-pill .dot { background: #28a745; box-shadow: 0 0 0 4px rgba(40, 167, 69, .14); }

  .glass {
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }

  .address-card { padding: 16px; }

  .icon-btn {
    border-radius: 8px;
    border: 1px solid #ced4da;
    background: #ffffff;
    color: #495057;
  }
  .icon-btn:hover:not(:disabled) {
    border-color: #80bdff;
    background: #f0f7ff;
    color: #0056b3;
    transform: none;
  }

  .url-box {
    height: 40px;
    border-radius: 20px;
    background: #ffffff;
    border: 1px solid #ced4da;
  }
  .url-box:focus-within {
    background: #ffffff;
    border-color: #80bdff;
    box-shadow: 0 0 0 3px rgba(0, 123, 255, .12);
  }
  #urlInput { color: #333333; }
  #urlInput::placeholder { color: #adb5bd; }

  .go-btn {
    height: 40px;
    padding: 0 24px;
    border-radius: 8px;
    background: #007bff;
    box-shadow: 0 3px 10px rgba(0, 123, 255, .22);
  }
  .go-btn:hover:not(:disabled) { background: #0069d9; box-shadow: 0 5px 14px rgba(0, 123, 255, .28); transform: none; }

  .stats {
    gap: 0;
    margin-top: 12px;
    border: 1px solid #b8daff;
    border-radius: 10px;
    overflow: hidden;
    background: #f0f7ff;
  }

  .stat {
    padding: 12px 16px;
    border: 0;
    border-right: 1px solid #b8daff;
    border-radius: 0;
    background: transparent;
  }
  .stat:last-child { border-right: 0; }
  .stat::before { display: none; }
  .stat .label { color: #6c757d; }
  .stat .value { color: #0056b3; font-size: 16px; }
  .stat.status .value.good { color: #28a745; }
  .stat.status .value.warn { color: #fd7e14; }
  .stat.status .value.bad { color: #dc3545; }
  .stat.status .value.neutral { color: #6c757d; }

  .chip {
    background: #ffffff;
    border: 1px solid #dee2e6;
    border-radius: 8px;
    color: #6c757d;
  }
  .chip b { color: #333333; }
  .meta-title { color: #333333; }
  .meta-note { color: #6c757d; }

  .browser-stage {
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
  }
  .browser-stage::before { display: none; }

  .empty-state {
    background: #ffffff;
    color: #6c757d;
  }
  .empty-state .big {
    background: #e7f1ff;
    border-color: #b8daff;
    border-radius: 10px;
    color: #0056b3;
  }
  .empty-state code { background: #f8f9fa; border-color: #dee2e6; color: #0056b3; }

  .history-card {
    padding: 18px;
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
  }
  .history-head h2 { color: #333333; }
  .history-head p { color: #6c757d; }
  .refresh-btn {
    background: #ffffff;
    border: 1px solid #ced4da;
    border-radius: 8px;
    color: #495057;
  }
  .refresh-btn:hover { background: #f0f7ff; border-color: #80bdff; color: #0056b3; }

  .table-wrap { border-radius: 10px; border-color: #dee2e6; background: #ffffff; }
  thead th {
    background: #f8f9fa;
    color: #495057;
    border-bottom-color: #dee2e6;
    backdrop-filter: none;
  }
  tbody td { color: #333333; border-bottom-color: #f1f3f5; }
  tbody tr:hover { background: #f0f7ff; }

  .status-badge { border-radius: 6px; }
  .status-badge.good { background: #d4edda; color: #155724; }
  .status-badge.info { background: #cce5ff; color: #004085; }
  .status-badge.warn { background: #fff3cd; color: #856404; }
  .status-badge.bad { background: #f8d7da; color: #721c24; }

  .toast { background: #333333; border-radius: 8px; }

  @media (max-width: 760px) {
    .app { padding: 12px; }
    .topbar { padding: 10px 12px; }
    .stats { grid-template-columns: 1fr 1fr; }
    .stat:nth-child(2) { border-right: 0; }
    .stat:nth-child(-n+2) { border-bottom: 1px solid #b8daff; }
  }

  /* === High contrast enterprise override === */
  :root {
    --ink: #333333;
    --muted: #495057;
    --line: #dee2e6;
    --blue: #0056b3;
    --blue-2: #007bff;
    --green: #28a745;
    --amber: #fd7e14;
    --red: #dc3545;
    --glass: #ffffff;
    --glass-strong: #ffffff;
  }

  body {
    color: var(--ink);
    background: #f4f6f9;
    background-image: none;
    overflow: hidden;
  }

  body::before, body::after { display: none; }

  .app { padding: 16px 20px 20px; gap: 14px; }

  .topbar {
    background: #007bff;
    border: 1px solid #0056b3;
    border-radius: 10px;
    padding: 12px 16px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, .08);
  }

  .brand h1 { color: #ffffff; font-size: 18px; }
  .brand p { color: rgba(255, 255, 255, .82); }
  .logo {
    background: #ffffff;
    color: #0056b3;
    box-shadow: 0 4px 12px rgba(0, 0, 0, .12);
  }

  .tabs {
    background: #f1f3f5;
    border: 1px solid var(--line);
    border-radius: 8px;
    box-shadow: none;
    backdrop-filter: none;
  }

  .tab-btn { color: #6c757d; border-radius: 6px; }
  .tab-btn:hover { color: #0056b3; background: #e9ecef; }
  .tab-btn.active { color: #0056b3; background: #ffffff; box-shadow: 0 1px 4px rgba(0, 0, 0, .08); }

  .live-pill {
    background: #d4edda;
    color: #155724;
    border-color: #c3e6cb;
  }

  .glass {
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }

  .address-card { padding: 16px; }

  .icon-btn {
    border-radius: 8px;
    border: 1px solid #ced4da;
    background: #ffffff;
    color: #495057;
  }
  .icon-btn:hover:not(:disabled) {
    border-color: #80bdff;
    background: #f0f7ff;
    color: #0056b3;
    transform: none;
  }

  .url-box {
    height: 40px;
    border-radius: 8px;
    background: #ffffff;
    border: 1px solid #ced4da;
  }
  .url-box:focus-within {
    background: #ffffff;
    border-color: #80bdff;
    box-shadow: 0 0 0 3px rgba(0, 123, 255, .12);
  }
  #urlInput { color: #333333; }
  #urlInput::placeholder { color: #adb5bd; }

  .go-btn {
    height: 40px;
    padding: 0 24px;
    border-radius: 8px;
    background: #007bff;
    box-shadow: 0 3px 10px rgba(0, 123, 255, .22);
  }
  .go-btn:hover:not(:disabled) {
    background: #0069d9;
    box-shadow: 0 5px 14px rgba(0, 123, 255, .28);
    transform: none;
  }

  .stats {
    gap: 0;
    margin-top: 12px;
    border: 1px solid #b8daff;
    border-radius: 10px;
    overflow: hidden;
    background: #f0f7ff;
  }
  .stat {
    padding: 12px 16px;
    border: 0;
    border-right: 1px solid #b8daff;
    border-radius: 0;
    background: transparent;
  }
  .stat:last-child { border-right: 0; }
  .stat::before { display: none; }
  .stat .label { color: #495057; }
  .stat .value { color: #333333; font-size: 16px; }
  .stat.status .value.good { color: #28a745; }
  .stat.status .value.warn { color: #fd7e14; }
  .stat.status .value.bad { color: #dc3545; }
  .stat.status .value.neutral { color: #6c757d; }

  .chip {
    background: #ffffff;
    border: 1px solid #dee2e6;
    border-radius: 8px;
    color: #6c757d;
  }
  .chip b { color: #333333; }
  .meta-title { color: #333333; }
  .meta-note { color: #6c757d; }

  .browser-stage {
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
  }
  .browser-stage::before { display: none; }
  .empty-state { background: #ffffff; color: #6c757d; }
  .empty-state .big {
    background: #e7f1ff;
    border-color: #b8daff;
    border-radius: 10px;
    color: #0056b3;
  }
  .empty-state code { background: #f8f9fa; border-color: #dee2e6; color: #0056b3; }

  .history-card {
    padding: 18px;
    background: #ffffff;
    border: 1px solid var(--line);
    border-radius: 10px;
    box-shadow: 0 4px 18px rgba(0, 0, 0, .06);
  }
  .history-head h2 { color: #333333; }
  .history-head p { color: #6c757d; }
  .refresh-btn {
    background: #ffffff;
    border: 1px solid #ced4da;
    border-radius: 8px;
    color: #495057;
  }
  .refresh-btn:hover { background: #f0f7ff; border-color: #80bdff; color: #0056b3; }

  .table-wrap { border-radius: 10px; border-color: #dee2e6; background: #ffffff; }
  thead th {
    background: #f8f9fa;
    color: #495057;
    border-bottom-color: #dee2e6;
    backdrop-filter: none;
  }
  tbody td { color: #333333; border-bottom-color: #f1f3f5; }
  tbody tr:nth-child(even) { background: #f8f9fa; }
  tbody tr:hover { background: #f0f7ff; }

  .status-badge { border-radius: 6px; }
  .status-badge.good { background: #d4edda; color: #155724; }
  .status-badge.info { background: #cce5ff; color: #004085; }
  .status-badge.warn { background: #fff3cd; color: #856404; }
  .status-badge.bad { background: #f8d7da; color: #721c24; }

  .toast { background: #333333; border-radius: 8px; }

  @media (max-width: 760px) {
    .app { padding: 12px; }
    .topbar { padding: 10px 12px; }
    .stats { grid-template-columns: 1fr 1fr; }
    .stat:nth-child(2) { border-right: 0; }
    .stat:nth-child(-n+2) { border-bottom: 1px solid #b8daff; }
  }
</style>
</head>
<body>
<div class="app">
  <header class="topbar">
    <div class="brand">
      <div class="logo">L7</div>
      <div>
        <h1>HTTP Server &amp; Client Dashboard</h1>
        <p>Raw Socket · MySQL · Enterprise Dashboard</p>
      </div>
    </div>

    <div class="spacer"></div>

    <div class="tabs" role="tablist">
      <button class="tab-btn active" id="browserTab" type="button">Browser</button>
      <button class="tab-btn" id="historyTab" type="button">History</button>
    </div>

    <div class="live-pill"><span class="dot"></span><span id="serverState">localhost</span></div>
  </header>

  <main class="shell">
    <section class="panel" id="browserPanel">
      <div class="glass address-card">
        <div class="nav-row">
          <button class="icon-btn" id="backBtn" type="button" title="Quay lại" disabled>‹</button>
          <button class="icon-btn" id="forwardBtn" type="button" title="Tiến tới" disabled>›</button>
          <button class="icon-btn" id="reloadBtn" type="button" title="Tải lại" disabled>↻</button>
          <div class="url-box">
            <span class="lock">⌁</span>
            <input id="urlInput" type="text" spellcheck="false"
                   placeholder="Nhập URL, ví dụ: https://example.com">
          </div>
          <button class="go-btn" id="goBtn" type="button">GO</button>
        </div>

        <div class="stats" aria-label="Thông số request">
          <div class="stat time">
            <div class="label">TIME</div>
            <div class="value" id="statTime">--:--:--</div>
          </div>
          <div class="stat ip">
            <div class="label">IP</div>
            <div class="value" id="statIp">—</div>
          </div>
          <div class="stat port">
            <div class="label">PORT</div>
            <div class="value" id="statPort">—</div>
          </div>
          <div class="stat status">
            <div class="label">STATUS</div>
            <div class="value neutral" id="statStatus">—</div>
          </div>
        </div>

        <div class="meta-row">
          <div class="meta-title" id="pageTitle">Chưa tải trang nào</div>
          <div class="chip">p <b id="chipP">0</b></div>
          <div class="chip">div <b id="chipDiv">0</b></div>
          <div class="chip">span <b id="chipSpan">0</b></div>
          <div class="chip">img <b id="chipImg">0</b></div>
          <div class="chip">a <b id="chipA">0</b></div>
          <div class="chip"><b id="chipBytes">0 B</b></div>
          <div class="chip"><b id="chipMs">0 ms</b></div>
          <div class="meta-note" id="metaNote">Sẵn sàng</div>
        </div>
      </div>

      <div class="glass browser-stage">
        <iframe id="frame" title="Nội dung trang web"
                sandbox="allow-scripts allow-forms allow-same-origin allow-popups"></iframe>
        <div class="empty-state" id="emptyState">
          <div class="inner">
            <div class="big">◎</div>
            <h3>Bắt đầu duyệt web</h3>
            <p>Nhập URL và bấm <b>GO</b>. Server sẽ tải trang bằng raw socket,
            phân tích IP/Port rồi trả JSON về đây.</p>
            <p style="margin-top:10px">Gợi ý:
              <code id="hintUrl">http://localhost:8080/gioi-thieu</code>
            </p>
          </div>
        </div>
      </div>
    </section>

    <section class="panel" id="historyPanel" hidden>
      <div class="glass history-card">
        <div class="history-head">
          <div>
            <h2>Lịch sử duyệt web</h2>
            <p>Đọc trực tiếp từ bảng <b>web_history</b> trong MySQL · tối đa 100 dòng</p>
          </div>
          <div class="spacer"></div>
          <button class="refresh-btn" id="refreshHistory" type="button">↻ Làm mới</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th style="width:76px">Time</th>
                <th style="width:180px">Title</th>
                <th>URL</th>
                <th style="width:120px">IP</th>
                <th style="width:62px">Port</th>
                <th style="width:78px">Status</th>
                <th style="width:78px">Bytes</th>
                <th style="width:64px">ms</th>
              </tr>
            </thead>
            <tbody id="historyBody">
              <tr><td colspan="8" class="table-empty">Chưa tải lịch sử.</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </main>
</div>

<div class="toast" id="toast"></div>

<script>
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);

  const els = {
    frame: $("frame"),
    empty: $("emptyState"),
    url: $("urlInput"),
    go: $("goBtn"),
    back: $("backBtn"),
    forward: $("forwardBtn"),
    reload: $("reloadBtn"),
    browserTab: $("browserTab"),
    historyTab: $("historyTab"),
    browserPanel: $("browserPanel"),
    historyPanel: $("historyPanel"),
    historyBody: $("historyBody"),
    refreshHistory: $("refreshHistory"),
    statTime: $("statTime"),
    statIp: $("statIp"),
    statPort: $("statPort"),
    statStatus: $("statStatus"),
    pageTitle: $("pageTitle"),
    chipP: $("chipP"),
    chipDiv: $("chipDiv"),
    chipSpan: $("chipSpan"),
    chipImg: $("chipImg"),
    chipA: $("chipA"),
    chipBytes: $("chipBytes"),
    chipMs: $("chipMs"),
    metaNote: $("metaNote"),
    toast: $("toast"),
    hintUrl: $("hintUrl"),
    serverState: $("serverState")
  };

  const state = {
    stack: [],
    index: -1,
    loading: false,
    activeTab: "browser"
  };

  function toast(message) {
    els.toast.textContent = message;
    els.toast.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => els.toast.classList.remove("show"), 3200);
  }

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function formatBytes(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / (1024 * 1024)).toFixed(1) + " MB";
  }

  function normalizeUrl(raw) {
    let value = String(raw || "").trim();
    if (!value) return "";
    if (value.startsWith("/")) return window.location.origin + value;
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) value = "http://" + value;
    return value;
  }

  function setTab(tab) {
    state.activeTab = tab;
    const browser = tab === "browser";
    els.browserTab.classList.toggle("active", browser);
    els.historyTab.classList.toggle("active", !browser);
    els.browserPanel.hidden = !browser;
    els.historyPanel.hidden = browser;
    if (!browser) loadHistory();
  }

  function setLoading(loading) {
    state.loading = loading;
    els.go.disabled = loading;
    els.go.innerHTML = loading ? '<span class="spin"></span>WAIT' : "GO";
    els.reload.disabled = loading || state.index < 0;
    updateNav();
  }

  function updateNav() {
    els.back.disabled = state.loading || state.index <= 0;
    els.forward.disabled = state.loading || state.index < 0 || state.index >= state.stack.length - 1;
    els.reload.disabled = state.loading || state.index < 0;
  }

  function statusClass(status) {
    const code = Number(status) || 0;
    if (code >= 200 && code < 300) return "good";
    if (code >= 300 && code < 400) return "info";
    if (code >= 400 && code < 500) return "warn";
    if (code >= 500) return "bad";
    return "neutral";
  }

  function statusCodeOf(data) {
    const direct = Number(data && data.statusCode);
    if (Number.isFinite(direct) && direct > 0) return direct;
    const match = String((data && (data.statusText || data.status)) || "").match(/^(\d{3})/);
    return match ? Number(match[1]) : 0;
  }

  function showStatus(status, text) {
    const code = Number(status) || 0;
    els.statStatus.textContent = text || (code > 0 ? String(code) : (code === 0 ? "ERR" : "—"));
    els.statStatus.className = "value " + statusClass(code);
  }

  function updateStats(data) {
    els.statTime.textContent = data.time || "--:--:--";
    els.statIp.textContent = data.ip || "—";
    els.statIp.title = data.ip || "";
    els.statPort.textContent = data.port ? String(data.port) : "—";
    showStatus(statusCodeOf(data), data.statusText);

    const analysis = data.analysis || {};
    els.chipP.textContent = analysis.p || 0;
    els.chipDiv.textContent = analysis.div || 0;
    els.chipSpan.textContent = analysis.span || 0;
    els.chipImg.textContent = analysis.img || 0;
    els.chipA.textContent = analysis.a || 0;
    els.chipBytes.textContent = formatBytes(data.bytes || 0);
    els.chipMs.textContent = (data.elapsedMs || 0) + " ms";
    els.pageTitle.textContent = data.title || data.finalUrl || "Không có tiêu đề";
    els.pageTitle.title = data.title || data.finalUrl || "";
    els.metaNote.textContent = data.contentType || "";
  }

  function showEmpty(message) {
    els.empty.hidden = false;
    if (message) {
      els.empty.querySelector("h3").textContent = "Không hiển thị được trang";
      els.empty.querySelector("p").textContent = message;
    }
    els.frame.srcdoc = "";
  }

  function showFrame(html) {
    els.empty.hidden = true;
    els.frame.srcdoc = html;
  }

  function errorHtml(message, detail) {
    return '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
      '<style>body{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#fff7f7;' +
      'color:#7f1d1d}.wrap{max-width:680px;margin:48px auto;padding:0 24px}' +
      '.card{background:#fff;border:1px solid #fecaca;border-radius:16px;padding:28px;' +
      'box-shadow:0 16px 40px rgba(127,29,29,.08)}h2{margin:0 0 10px;font-size:21px}' +
      'pre{white-space:pre-wrap;word-break:break-word;background:#fff7f7;border:1px solid #fee2e2;' +
      'border-radius:10px;padding:14px;color:#991b1b;font-family:Consolas,monospace;font-size:12px}' +
      '</style></head><body><div class="wrap"><div class="card"><h2>' + esc(message) +
      '</h2><pre>' + esc(detail) + '</pre></div></div></body></html>';
  }

  async function navigate(rawUrl, push) {
    const url = normalizeUrl(rawUrl);
    if (!url || state.loading) return;

    if (push !== false) {
      while (state.stack.length > state.index + 1) state.stack.pop();
      if (state.index < 0 || state.stack[state.index] !== url) {
        state.stack.push(url);
        state.index = state.stack.length - 1;
      }
    }
    els.url.value = url;
    setLoading(true);
    els.metaNote.textContent = "Đang tải " + url + " …";
    updateNav();

    try {
      const response = await fetch("/proxy?url=" + encodeURIComponent(url), {
        headers: { "Accept": "application/json" }
      });
      const data = await response.json();

      if (!data.ok) {
        updateStats(data);
        els.pageTitle.textContent = "Lỗi tải trang";
        showFrame(errorHtml("Không tải được URL", (data.message || "Lỗi không xác định") +
          "\n\n" + (data.requestedUrl || url)));
        els.metaNote.textContent = "Network error";
        toast(data.message || "Không tải được URL");
        return;
      }

      updateStats(data);
      els.url.value = data.finalUrl || url;
      if (state.index >= 0 && state.stack[state.index] === url) {
        state.stack[state.index] = data.finalUrl || url;
      }

      if (data.isHtml && data.html) {
        showFrame(data.html);
      } else {
        showFrame(errorHtml("Nội dung không phải HTML",
          (data.message || "Server trả về Content-Type: " + (data.contentType || "không rõ")) +
          "\n\nHTTP " + (data.statusText || data.status || "") +
          "\n" + (data.bytes || 0) + " byte · " + (data.elapsedMs || 0) + " ms"));
      }

      els.metaNote.textContent = data.historySaved
        ? "Đã lưu lịch sử MySQL"
        : (data.historyError ? "Không lưu được lịch sử" : "Hoàn tất");
      if (data.finalUrl) document.title = (data.title ? data.title + " · " : "") + "Lab 7 Web Client";
    } catch (error) {
      els.statTime.textContent = new Date().toLocaleTimeString("vi-VN", { hour12: false });
      els.statIp.textContent = "—";
      els.statPort.textContent = "—";
      showStatus(0, "ERR");
      els.pageTitle.textContent = "Lỗi kết nối";
      els.metaNote.textContent = "Không gọi được /proxy";
      showFrame(errorHtml("Không gọi được server", String(error && error.message ? error.message : error)));
      toast("Không kết nối được server localhost.");
    } finally {
      setLoading(false);
      updateNav();
    }
  }

  function goBack() {
    if (state.index > 0) {
      state.index -= 1;
      updateNav();
      navigate(state.stack[state.index], false);
    }
  }

  function goForward() {
    if (state.index >= 0 && state.index < state.stack.length - 1) {
      state.index += 1;
      updateNav();
      navigate(state.stack[state.index], false);
    }
  }

  function reloadPage() {
    if (state.index >= 0) navigate(state.stack[state.index], false);
  }

  async function loadHistory() {
    els.historyBody.innerHTML = '<tr><td colspan="8" class="table-empty">Đang tải lịch sử…</td></tr>';
    try {
      const response = await fetch("/history", { headers: { "Accept": "application/json" } });
      const data = await response.json();
      if (!data.ok) {
        els.historyBody.innerHTML = '<tr><td colspan="8" class="table-empty">' +
          esc(data.error || "Không đọc được lịch sử.") + '</td></tr>';
        return;
      }
      if (!data.history || data.history.length === 0) {
        els.historyBody.innerHTML = '<tr><td colspan="8" class="table-empty">' +
          'Chưa có lịch sử. Hãy duyệt một trang trước.</td></tr>';
        return;
      }
      els.historyBody.innerHTML = data.history.map((row) => {
        const cls = statusClass(row.status);
        const title = row.title || "(không tiêu đề)";
        return '<tr data-url="' + esc(row.url) + '">' +
          '<td class="mono">' + esc(row.time || "") + '</td>' +
          '<td class="title-cell" title="' + esc(title) + '">' + esc(title) + '</td>' +
          '<td class="mono url-cell" title="' + esc(row.finalUrl || row.url) + '">' +
            esc(row.finalUrl || row.url) + '</td>' +
          '<td class="mono">' + esc(row.ip || "—") + '</td>' +
          '<td class="mono">' + esc(row.port || "—") + '</td>' +
          '<td><span class="status-badge ' + cls + '">' + esc(row.statusText || row.status) + '</span></td>' +
          '<td class="mono">' + formatBytes(row.bytes) + '</td>' +
          '<td class="mono">' + esc(row.elapsedMs || 0) + '</td>' +
          '</tr>';
      }).join("");
    } catch (error) {
      els.historyBody.innerHTML = '<tr><td colspan="8" class="table-empty">' +
        esc("Không kết nối được /history: " + (error && error.message ? error.message : error)) +
        '</td></tr>';
    }
  }

  els.go.addEventListener("click", () => navigate(els.url.value, true));
  els.url.addEventListener("keydown", (event) => {
    if (event.key === "Enter") navigate(els.url.value, true);
  });
  els.back.addEventListener("click", goBack);
  els.forward.addEventListener("click", goForward);
  els.reload.addEventListener("click", reloadPage);
  els.browserTab.addEventListener("click", () => setTab("browser"));
  els.historyTab.addEventListener("click", () => setTab("history"));
  els.refreshHistory.addEventListener("click", loadHistory);

  els.historyBody.addEventListener("click", (event) => {
    const row = event.target.closest("tr[data-url]");
    if (!row) return;
    setTab("browser");
    navigate(row.getAttribute("data-url"), true);
  });

  window.addEventListener("message", (event) => {
    const data = event.data;
    if (!data || data.type !== "lab7:navigate" || typeof data.url !== "string") return;
    if (event.source !== els.frame.contentWindow) return;
    if (!/^https?:\/\//i.test(data.url)) return;
    if (state.loading) return;
    navigate(data.url, true);
  });

  window.addEventListener("error", (event) => {
    if (event && event.message) toast(event.message);
  });

  const origin = window.location.origin && window.location.origin !== "null"
    ? window.location.origin : "http://localhost:8080";
  els.hintUrl.textContent = origin + "/gioi-thieu";
  els.serverState.textContent = location.host || "localhost:8080";
  els.url.value = origin + "/gioi-thieu";

  if (location.protocol === "http:" || location.protocol === "https:") {
    navigate(els.url.value, true);
    if (new URLSearchParams(location.search).get("tab") === "history") {
      setTimeout(() => setTab("history"), 900);
    }
  } else {
    showEmpty("Hãy chạy server rồi mở http://localhost:8080/ để dùng Web Client.");
  }
})();
</script>
</body>
</html>
```

## `run.bat`

_Compile 5 file Java và chạy ứng dụng_

```bat
@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

rem =====================================================================
rem  Lab 7 - Network Web Server and Client
rem  Build 5 Java files and run JavaFX application.
rem  Requires JDK 21+ and JavaFX SDK 21 in libs.
rem =====================================================================

if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVAC=javac"
    set "JAVA=java"
)

set "JAVAFX=libs\javafx-sdk-21.0.5\lib"

if not exist "%JAVAFX%\javafx.controls.jar" (
    echo [LOI] Khong tim thay JavaFX SDK tai %JAVAFX%
    echo       Tai JavaFX SDK 21.0.5 va giai nen vao thu muc libs.
    pause
    exit /b 1
)
if not exist "libs\mysql-connector-j-9.4.0.jar" (
    echo [LOI] Thieu libs\mysql-connector-j-9.4.0.jar
    pause
    exit /b 1
)
if not exist "libs\gson-2.11.0.jar" (
    echo [LOI] Thieu libs\gson-2.11.0.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-core-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-core-12.3.1.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-javafx-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-javafx-12.3.1.jar
    pause
    exit /b 1
)
if not exist "libs\ikonli-fontawesome5-pack-12.3.1.jar" (
    echo [LOI] Thieu libs\ikonli-fontawesome5-pack-12.3.1.jar
    pause
    exit /b 1
)

if exist out rmdir /s /q out
mkdir out
if not exist out\ui mkdir out\ui

echo [1/2] Dang compile 5 file Java...
"%JAVAC%" -encoding UTF-8 -d out -cp "libs/*" ^
    --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.web ^
    src\App.java ^
    src\core\NetworkServer.java ^
    src\core\NetworkClient.java ^
    src\core\DatabaseManager.java ^
    src\ui\MainView.java

if errorlevel 1 (
    echo.
    echo [LOI] Compile that bai. Xem thong bao phia tren.
    pause
    exit /b 1
)

copy /Y "src\ui\style.css" "out\ui\style.css" >nul
echo [2/2] Dang khoi dong ung dung...
echo.
echo     Server mac dinh : http://localhost:8080
echo     Web Client      : http://localhost:8080/  (sau khi bam Bat server)
echo     MySQL           : localhost:3307 / root / mat khau rong
echo.

"%JAVA%" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --module-path "%JAVAFX%" --add-modules javafx.controls,javafx.web -cp "out;libs/*" App

endlocal
```

## `README.md`

_Hướng dẫn cài đặt và chạy_

```markdown
# Lab 7 — Web Server & Client (JavaFX + MySQL + Raw Socket)

Ứng dụng desktop JavaFX kết hợp web client, web server HTTP/1.1 viết tay trên raw
`Socket` và cơ sở dữ liệu MySQL (XAMPP, cổng **3307**). Giao diện desktop và web
đều ở **Light Mode**.

## Cấu trúc bắt buộc

```text
F:\Lab7_WebServer
├── src
│   ├── App.java
│   ├── core
│   │   ├── NetworkServer.java
│   │   ├── NetworkClient.java
│   │   └── DatabaseManager.java
│   └── ui
│       ├── MainView.java
│       └── style.css
├── web
│   └── index.html
├── libs/
├── web_data.json
├── web_history.json
├── run.bat
└── .gitignore
```

## Tính năng chính

- **Raw socket**: `GET`, `POST`, `HEAD`, HTTP/1.1, chunked, gzip/deflate, redirect.
- **MySQL 3307**: tự tạo database/bảng, seed 4 trang, lưu lịch sử và export JSON.
- **Phân giải thật IP/Port** bằng `InetAddress`, hiển thị `Time | IP | Port | Status`.
- **Rewrite link** trong HTML thành `javascript:loadUrl(...)` và chèn bridge script.
- **Desktop UI**: Enterprise Light Mode, header trắng, icon Ikonli FontAwesome 5,
  4 thẻ thống kê, bảng Request có icon, thanh URL Search + nút SEND, badge
  `Time | IP | Port | Status`, cùng các tab Dashboard / Client Browser / Database.
- **Web UI**: light glassmorphism, URL bar, GO, Back/Forward, iframe, History.
- **Server endpoints**: `/`, `/proxy`, `/history`, `/__health`, `/web_data.json`, `/web_history.json`.

## Yêu cầu

- JDK 21+
- JavaFX SDK 21.0.5 tại `libs/javafx-sdk-21.0.5/lib`
- MySQL Connector/J, Gson, SLF4J trong `libs`
- Ikonli 12.3.1: `ikonli-core`, `ikonli-javafx`, `ikonli-fontawesome5-pack`
- MySQL/MariaDB XAMPP đang chạy tại `127.0.0.1:3307`, user `root`, mật khẩu rỗng

## Chạy chương trình

```bat
run.bat
```

`run.bat` sẽ compile đúng 5 file Java, copy `style.css` vào `out/ui`, rồi chạy `App`.

Sau khi ứng dụng mở:

1. Tab **Dashboard** → bấm **Bật Server**.
2. Mở web client tại `http://localhost:8080/`.
3. Tab **Client Browser** để duyệt URL bằng raw socket; nhập URL rồi bấm **SEND**.
4. Tab **Database** để xem lịch sử và export `web_data.json`, `web_history.json`.

## Kiểm tra nhanh

```bat
curl -i http://localhost:8080/
curl -I http://localhost:8080/sinh-vien
curl -i -X POST -d "ho_ten=Nguyen%20Van%20A&ma_sv=24IT277" http://localhost:8080/lien-he
curl -i http://localhost:8080/__health
curl -i http://localhost:8080/history
```

## Ghi chú

- Các file JSON là file tự sinh, đã được `.gitignore` bỏ qua.
- Thư mục `out/`, `*.class`, `.vscode` đã được `.gitignore` bỏ qua.
- Nếu MySQL chưa chạy, server vẫn bật; các request proxy sẽ báo `historySaved = false`.
```
