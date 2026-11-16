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

        if ("/history".equals(path) || "/api/history".equals(path)) {
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
