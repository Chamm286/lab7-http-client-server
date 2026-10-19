package core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class HttpServerCore {
    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    private static final int MAX_BROWSER_BODY_BYTES = 1024 * 1024;
    private static final int MAX_BROWSER_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BROWSER_REQUESTS_PER_MINUTE = 30;
    public interface ServerLogger {
        void log(String message);
    }

    public static final class RequestInfo {
        public final String time;
        public final String method;
        public final String path;
        public final int statusCode;
        public final long durationMillis;
        public final long bytes;
        public final String clientAddress;

        private RequestInfo(String time, String method, String path, int statusCode,
                long durationMillis, long bytes, String clientAddress) {
            this.time = time;
            this.method = method;
            this.path = path;
            this.statusCode = statusCode;
            this.durationMillis = durationMillis;
            this.bytes = bytes;
            this.clientAddress = clientAddress;
        }
    }

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workers;
    private volatile boolean running;
    private volatile Path documentRoot;
    private volatile ServerLogger logger;
    private volatile Consumer<RequestInfo> requestListener;
    private volatile int port;
    private volatile String writeToken;
    private volatile AuditStore auditStore;
    private volatile long startedAtMillis;
    private final Map<String, ProxyRateWindow> browserRateWindows = new ConcurrentHashMap<>();
    private int browserRequestCounter;

    public synchronized void start(int port, Path documentRoot, ServerLogger logger,
            Consumer<RequestInfo> requestListener) throws IOException {
        if (running) {
            throw new IllegalStateException("Server đang chạy.");
        }
        Path root = documentRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Thư mục nội dung không tồn tại: " + root);
        }

        Path dataDirectory = root.getParent() == null
                ? Path.of(System.getProperty("user.home"), ".lab7-data")
                : root.getParent().resolve("lab7-data");
        AuditStore persistentAudit = new AuditStore(dataDirectory);
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        // Bind explicitly to IPv4 wildcard. This guarantees that both
        // 127.0.0.1/localhost and the machine's LAN IPv4 address reach the
        // same listener, independent of the JVM's IPv6 wildcard behavior.
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        this.port = socket.getLocalPort();
        this.documentRoot = root.toRealPath();
        this.auditStore = persistentAudit;
        this.writeToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        this.startedAtMillis = System.currentTimeMillis();
        this.logger = logger;
        this.requestListener = requestListener;
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "http-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.serverSocket = socket;
        this.running = true;

        Thread acceptThread = new Thread(() -> acceptConnections(socket), "http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.log("Server lắng nghe trên mọi card mạng tại cổng " + this.port + ".");
        String lanAddress = getLanAddress();
        logger.log(lanAddress == null
                ? "Không tìm thấy IPv4 LAN; kiểm tra card mạng đang kết nối."
                : "Client cùng mạng kết nối tại http://" + lanAddress + ":" + this.port + "/");
        logger.log("Thư mục phục vụ: " + this.documentRoot);
        logger.log("Dữ liệu bền vững: " + dataDirectory.toAbsolutePath().normalize());
        logger.log("GET công khai; POST/PUT/DELETE và /__audit cần Bearer token.");
    }

    public synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                log("Không thể đóng server socket: " + e.getMessage());
            }
        }
        ExecutorService executor = workers;
        workers = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        writeToken = null;
        log("Server đã dừng.");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public String getWriteToken() {
        return writeToken;
    }

    public static String getLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> available = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || networkInterface.isVirtual() || isVirtualAdapter(networkInterface)) {
                    continue;
                }
                available.add(networkInterface);
            }
            for (boolean preferWifi : new boolean[] { true, false }) {
                for (NetworkInterface networkInterface : available) {
                    String name = (networkInterface.getName() + " "
                            + networkInterface.getDisplayName()).toLowerCase(Locale.ROOT);
                    boolean isWifi = name.contains("wifi") || name.contains("wi-fi")
                            || name.contains("wireless") || name.startsWith("wlan");
                    if (isWifi != preferWifi) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()
                                && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            return null;
        }
        return null;
    }

    private static boolean isVirtualAdapter(NetworkInterface networkInterface) {
        String name = (networkInterface.getName() + " "
                + networkInterface.getDisplayName()).toLowerCase(Locale.ROOT);
        return name.contains("virtualbox") || name.contains("vmware")
                || name.contains("hyper-v") || name.contains("wsl")
                || name.contains("docker") || name.contains("wi-fi direct")
                || name.contains("wan miniport") || name.contains("bluetooth")
                || name.contains("tunnel") || name.contains("npcap");
    }

    private void acceptConnections(ServerSocket socket) {
        while (running && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(15_000);
                log("TCP accept " + client.getInetAddress().getHostAddress()
                        + ":" + client.getPort());
                ExecutorService executor = workers;
                if (executor != null) {
                    executor.submit(() -> handleClient(client));
                } else {
                    client.close();
                }
            } catch (SocketException e) {
                if (running) {
                    log("Lỗi socket server: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running) {
                    log("Không thể nhận kết nối: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        long startedAt = System.nanoTime();
        String method = "-";
        String path = "/";
        String requestTarget = "/";
        String protocol = "HTTP/1.1";
        int status = 400;
        long bytesSent = 0;
        String remoteAddress = client.getInetAddress().getHostAddress();
        try (Socket socket = client;
                InputStream in = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            try {
                String requestLine = readLine(in);
                if (requestLine == null) {
                    return;
                }
                log("← " + remoteAddress + "  " + requestLine);
                String[] parts = requestLine.split(" ", -1);
                if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()
                        || !parts[0].matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new HttpProtocolException(400, "Bad Request",
                            "Request line phải có dạng METHOD /path HTTP/1.x.");
                }
                method = parts[0].toUpperCase(Locale.ROOT);
                protocol = parts[2];
                if (!"HTTP/1.0".equals(protocol) && !"HTTP/1.1".equals(protocol)) {
                    protocol = "HTTP/1.1";
                    throw new HttpProtocolException(505, "HTTP Version Not Supported",
                            "Máy chủ hỗ trợ HTTP/1.0 và HTTP/1.1.");
                }
                requestTarget = parts[1];
                path = requestPath(requestTarget, method);

                Map<String, String> headers = readHeaders(in);
                if ("HTTP/1.1".equals(protocol)
                        && (headers.get("host") == null || headers.get("host").isEmpty())) {
                    throw new HttpProtocolException(400, "Bad Request",
                            "Request HTTP/1.1 phải có header Host không rỗng.");
                }
                if (headers.containsKey("content-length")
                        && headers.containsKey("transfer-encoding")) {
                    throw new HttpProtocolException(400, "Bad Request",
                            "Không gửi đồng thời Content-Length và Transfer-Encoding.");
                }
                if ("HTTP/1.0".equals(protocol) && headers.containsKey("transfer-encoding")) {
                    throw new HttpProtocolException(400, "Bad Request",
                            "Transfer-Encoding: chunked chỉ hỗ trợ với HTTP/1.1.");
                }
                headers.forEach((name, value) -> {
                    if (!"cookie".equals(name) && !"authorization".equals(name)
                            && !"proxy-authorization".equals(name)) {
                        log("   " + name + ": " + value);
                    }
                });

                if (!List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                        .contains(method)) {
                    FileResponse response = errorResponse(501, "Not Implemented",
                            "Phương thức chưa được cài đặt trên máy chủ.");
                    status = response.statusCode;
                    bytesSent = response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, false, Map.of());
                } else if (requiresWritePermission(method, path)
                        && !isAuthorized(headers.get("authorization"))) {
                    FileResponse response = errorResponse(401, "Unauthorized",
                            "Thao tác này cần quyền ghi. Gửi header Authorization: Bearer <token>.");
                    status = response.statusCode;
                    bytesSent = response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, false,
                            Map.of("WWW-Authenticate", "Bearer realm=\"Lab7 HTTP Server\""));
                } else if (!java.util.Arrays.asList(allowedMethods(path).split(", "))
                        .contains(method)) {
                    FileResponse response = methodNotAllowed(method, path);
                    status = response.statusCode;
                    bytesSent = response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, false, response.headers);
                } else if ("OPTIONS".equals(method)) {
                    String allow = allowedMethods(path);
                    byte[] body = ("Allowed methods: " + allow + "\n"
                            + "POST form: /__submit; file upload: /__upload")
                            .getBytes(StandardCharsets.UTF_8);
                    status = 200;
                    bytesSent = body.length;
                    writeResponse(out, protocol, status, "OK", "text/plain; charset=UTF-8",
                            body, false, Map.of("Allow", allow));
                } else if ("POST".equals(method) || "PUT".equals(method)) {
                    if ("/__browse".equals(path) && "POST".equals(method)
                            && declaredBodyLength(headers) > MAX_BROWSER_BODY_BYTES) {
                        throw new HttpProtocolException(413, "Payload Too Large",
                                "Body POST qua Browser tối đa 1 MB.");
                    }
                    byte[] requestBody = readRequestBody(in, headers,
                            "/__browse".equals(path) ? MAX_BROWSER_BODY_BYTES : MAX_UPLOAD_BYTES);
                    FileResponse response;
                    if ("/__browse".equals(path) && "POST".equals(method)) {
                        response = fetchExternalResource(requestTarget, method, requestBody,
                                headers.get("content-type"), remoteAddress);
                    } else if ("POST".equals(method) && "/__upload".equals(path)) {
                        response = saveUpload(headers.get("x-file-name"), requestBody);
                    } else if ("POST".equals(method) && "/__submit".equals(path)) {
                        response = submitForm(requestBody, headers.get("content-type"));
                    } else if ("POST".equals(method) && path.startsWith("/uploads/")) {
                        String name = uploadPathName(path);
                        response = saveUpload(name == null ? null
                                : java.net.URLEncoder.encode(name, StandardCharsets.UTF_8),
                                requestBody);
                    } else {
                        response = replaceUpload(path, requestBody);
                    }
                    if ("POST".equals(method)) {
                        logRequestBody(path, headers.get("content-type"), requestBody);
                    }
                    status = response.statusCode;
                    bytesSent = response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, false, response.headers);
                    log(method + " nhận " + requestBody.length + " bytes.");
                } else if ("DELETE".equals(method)) {
                    FileResponse response = deleteUpload(path);
                    status = response.statusCode;
                    bytesSent = response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, false, response.headers);
                } else if ("GET".equals(method) || "HEAD".equals(method)) {
                    FileResponse response;
                    try {
                        response = "/__browse".equals(path)
                                ? fetchExternalResource(requestTarget, method, null, null, remoteAddress)
                                : "/__url-info".equals(path)
                                        ? inspectUrl(requestTarget, remoteAddress)
                                : "/__health".equals(path) ? healthResponse()
                                : "/__audit".equals(path) ? auditResponse()
                                        : findFile(path);
                    } catch (IOException e) {
                        log("Không thể đọc file " + path + ": " + e.getMessage());
                        response = errorResponse(500, "Internal Server Error",
                                "Không thể đọc nội dung file.");
                    }
                    status = response.statusCode;
                    bytesSent = "HEAD".equals(method) ? 0 : response.body.length;
                    writeResponse(out, protocol, status, response.reason, response.contentType,
                            response.body, "HEAD".equals(method), response.headers);
                }
                log("   Response body sent: " + bytesSent + " bytes");
            } catch (HttpProtocolException e) {
                status = e.statusCode;
                byte[] body = e.getMessage().getBytes(StandardCharsets.UTF_8);
                boolean headOnly = "HEAD".equals(method);
                bytesSent = headOnly ? 0 : body.length;
                Map<String, String> errorHeaders = e.statusCode == 405
                        ? Map.of("Allow", allowedMethods(path))
                        : e.statusCode == 401
                                ? Map.of("WWW-Authenticate", "Bearer realm=\"Lab7 HTTP Server\"")
                                : Map.of();
                writeResponse(out, protocol, status, e.reason, "text/plain; charset=UTF-8",
                        body, headOnly, errorHeaders);
                log("   Request rejected: " + e.getMessage());
            }
        } catch (IOException e) {
            log("Lỗi xử lý request từ " + remoteAddress + ": " + e.getMessage());
            status = 500;
        } finally {
            AuditStore store = auditStore;
            if (store != null && !"-".equals(method)) {
                try {
                    store.append(remoteAddress, method, path, status,
                            (System.nanoTime() - startedAt) / 1_000_000, bytesSent);
                } catch (IOException e) {
                    log("Không thể lưu audit log bền vững: " + e.getMessage());
                }
            }
            Consumer<RequestInfo> listener = requestListener;
            if (listener != null) {
                long duration = (System.nanoTime() - startedAt) / 1_000_000;
                listener.accept(new RequestInfo(java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                        method, path, status, duration, bytesSent, remoteAddress));
            }
        }
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int totalBytes = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new HttpProtocolException(400, "Bad Request",
                        "Request kết thúc trước dòng trống sau HTTP headers.");
            }
            if (line.isEmpty()) {
                return headers;
            }
            totalBytes += line.length() + 2;
            if (totalBytes > 64 * 1024) {
                throw new HttpProtocolException(431, "Request Header Fields Too Large",
                        "Tổng kích thước HTTP headers vượt quá 64 KB.");
            }
            int separator = line.indexOf(':');
            if (separator <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpProtocolException(400, "Bad Request",
                        "HTTP header không đúng định dạng Tên: Giá trị.");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) {
                throw new HttpProtocolException(400, "Bad Request",
                        "Tên HTTP header không hợp lệ.");
            }
            String value = line.substring(separator + 1).trim();
            if (value.indexOf('\0') >= 0) {
                throw new HttpProtocolException(400, "Bad Request",
                        "HTTP header chứa ký tự không hợp lệ.");
            }
            if (headers.containsKey(name)) {
                if ("content-length".equals(name) || "host".equals(name)
                        || "transfer-encoding".equals(name)) {
                    throw new HttpProtocolException(400, "Bad Request",
                            "Header " + name + " không được lặp lại.");
                }
                headers.put(name, headers.get(name) + ", " + value);
            } else {
                headers.put(name, value);
            }
        }
    }

    private byte[] readRequestBody(InputStream in, Map<String, String> headers)
            throws IOException {
        return readRequestBody(in, headers, MAX_UPLOAD_BYTES);
    }

    private byte[] readRequestBody(InputStream in, Map<String, String> headers, long maxBytes)
            throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null) {
            if (!"chunked".equalsIgnoreCase(transferEncoding)) {
                throw new HttpProtocolException(501, "Not Implemented",
                        "Máy chủ chỉ hỗ trợ Transfer-Encoding: chunked.");
            }
            return readChunkedBody(in, maxBytes);
        }
        String contentLength = headers.get("content-length");
        if (contentLength == null) {
            throw new HttpProtocolException(411, "Length Required",
                    "Request có body cần Content-Length hoặc Transfer-Encoding: chunked.");
        }
        final long length;
        try {
            length = Long.parseLong(contentLength);
        } catch (NumberFormatException e) {
            throw new HttpProtocolException(400, "Bad Request", "Content-Length không hợp lệ.");
        }
        if (length < 0) {
            throw new HttpProtocolException(400, "Bad Request", "Content-Length không hợp lệ.");
        }
        if (length > maxBytes) {
            throw new HttpProtocolException(413, "Payload Too Large",
                    "Request body vượt giới hạn " + formatByteLimit(maxBytes) + ".");
        }
        return readBody(in, length);
    }

    private byte[] readChunkedBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new HttpProtocolException(400, "Bad Request",
                        "Request chunk kết thúc trước khi hoàn tất.");
            }
            int extension = line.indexOf(';');
            String sizeText = (extension < 0 ? line : line.substring(0, extension)).trim();
            final long chunkSize;
            try {
                if (sizeText.isEmpty() || !sizeText.matches("[0-9A-Fa-f]+")) {
                    throw new NumberFormatException();
                }
                chunkSize = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new HttpProtocolException(400, "Bad Request", "Kích thước chunk không hợp lệ.");
            }
            if (chunkSize == 0) {
                int trailerBytes = 0;
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null) {
                        throw new HttpProtocolException(400, "Bad Request",
                                "Chunked body thiếu dòng kết thúc trailer.");
                    }
                    if (trailer.isEmpty()) {
                        return body.toByteArray();
                    }
                    trailerBytes += trailer.length() + 2;
                    if (trailerBytes > 8192 || trailer.indexOf(':') <= 0) {
                        throw new HttpProtocolException(400, "Bad Request",
                                "Trailer của chunked body không hợp lệ.");
                    }
                }

            }
            if (chunkSize > maxBytes - body.size()) {
                throw new HttpProtocolException(413, "Payload Too Large",
                        "Request body vượt giới hạn " + formatByteLimit(maxBytes) + ".");
            }
            byte[] chunk = readBody(in, chunkSize);
            body.write(chunk);
            if (in.read() != '\r' || in.read() != '\n') {
                throw new HttpProtocolException(400, "Bad Request",
                        "Chunk phải kết thúc bằng CRLF.");
            }
        }
    }

    private long declaredBodyLength(Map<String, String> headers) throws HttpProtocolException {
        String value = headers.get("content-length");
        if (value == null) {
            return 0;
        }
        try {
            long length = Long.parseLong(value);
            if (length < 0) {
                throw new NumberFormatException();
            }
            return length;
        } catch (NumberFormatException e) {
            throw new HttpProtocolException(400, "Bad Request", "Content-Length không hợp lệ.");
        }
    }

    private String formatByteLimit(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB"
                : (bytes / 1024) + " KB";
    }

    private FileResponse submitForm(byte[] body, String contentType) {
        String encodedForm = new String(body, StandardCharsets.UTF_8);
        StringBuilder fields = new StringBuilder();
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT)
                        .startsWith("application/x-www-form-urlencoded")) {
            try {
                for (String pair : encodedForm.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int equals = pair.indexOf('=');
                    String name = java.net.URLDecoder.decode(
                            equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(
                            equals < 0 ? "" : pair.substring(equals + 1), StandardCharsets.UTF_8);
                    fields.append("<li><strong>").append(htmlEscape(name))
                            .append("</strong>: ").append(htmlEscape(value)).append("</li>");
                }
            } catch (IllegalArgumentException e) {
                return errorResponse(400, "Bad Request",
                        "Biểu mẫu URL-encoded chứa ký tự phần trăm không hợp lệ.");
            }
        }
        if (fields.length() == 0) {
            fields.append("<li>Dữ liệu nhận: ").append(htmlEscape(encodedForm)).append("</li>");
        }
        String html = "<!doctype html><html lang=\"vi\"><head><meta charset=\"UTF-8\">"
                + "<title>POST đã nhận</title><style>body{font:16px Segoe UI, sans-serif;"
                + "max-width:760px;margin:48px auto;padding:0 24px;color:#182536}"
                + "main{border:1px solid #dce6ec;border-radius:12px;padding:24px}"
                + "h1{color:#155e75}li{margin:10px 0}</style></head><body><main>"
                + "<h1>Máy chủ đã nhận HTTP POST</h1>"
                + "<p>Body được đọc theo Content-Length / Transfer-Encoding và xử lý thành công.</p>"
                + "<p><strong>Content-Type:</strong> " + htmlEscape(contentType == null
                        ? "không được cung cấp" : contentType) + "</p>"
                + "<p><strong>Kích thước body:</strong> " + body.length + " bytes</p>"
                + "<div><h2>Dữ liệu biểu mẫu</h2><ul>" + fields + "</ul></div>"
                + "</main></body></html>";
        return new FileResponse(200, "OK", "text/html; charset=UTF-8",
                html.getBytes(StandardCharsets.UTF_8));
    }

    private String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private void logRequestBody(String path, String contentType, byte[] body) {
        if ("/__upload".equals(path) || path.startsWith("/uploads/")
                || contentType == null) {
            return;
        }
        String normalizedType = contentType.toLowerCase(Locale.ROOT);
        if (!normalizedType.startsWith("application/x-www-form-urlencoded")
                && !normalizedType.startsWith("text/")
                && !normalizedType.contains("json") && !normalizedType.contains("xml")) {
            return;
        }
        int previewLength = Math.min(body.length, 2048);
        String preview = new String(body, 0, previewLength, StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        log("   Body (UTF-8): " + preview
                + (body.length > previewLength ? " … [truncated]" : ""));
    }

    private String allowedMethods(String path) {
        if ("*".equals(path)) {
            return "GET, HEAD, POST, PUT, DELETE, OPTIONS";
        }
        if ("/__browse".equals(path)) {
            return "GET, HEAD, POST, OPTIONS";
        }
        if ("/__url-info".equals(path)) {
            return "GET, HEAD, OPTIONS";
        }
        if ("/__upload".equals(path)) {
            return "POST, OPTIONS";
        }
        if ("/__submit".equals(path)) {
            return "POST, OPTIONS";
        }
        if ("/__health".equals(path) || "/__audit".equals(path)
                || "/__files".equals(path)) {
            return "GET, HEAD, OPTIONS";
        }
        if (path.startsWith("/uploads/") && uploadPathName(path) != null) {
            return "GET, HEAD, POST, PUT, DELETE, OPTIONS";
        }
        return "GET, HEAD, OPTIONS";
    }

    private FileResponse methodNotAllowed(String method, String path) {
        String allow = allowedMethods(path);
        return new FileResponse(405, "Method Not Allowed", "text/plain; charset=UTF-8",
                (method + " không được phép với " + path)
                        .getBytes(StandardCharsets.UTF_8),
                Map.of("Allow", allow));
    }

    private boolean requiresWritePermission(String method, String path) {
        if ("/__browse".equals(path)) {
            return false;
        }
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)
                || "/__audit".equals(path) && ("GET".equals(method) || "HEAD".equals(method));
    }

    private boolean isAuthorized(String authorization) {
        String activeToken = writeToken;
        if (activeToken == null || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] expected = activeToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private FileResponse fetchExternalResource(String requestTarget, String method,
            byte[] requestBody, String contentType, String clientAddress) {
        URI target;
        try {
            String encodedUrl = URI.create(requestTarget).getRawQuery();
            String externalUrl = queryParameter(encodedUrl, "url");
            if (externalUrl == null || externalUrl.isBlank()) {
                return errorResponse(400, "Bad Request",
                        "Thiếu URL đích. Dùng /__browse?url=<encoded-http(s)-url>.");
            }
            target = URI.create(externalUrl);
            validateBrowserTarget(target);
        } catch (IllegalArgumentException e) {
            return errorResponse(400, "Bad Request", "URL đích không hợp lệ.");
        } catch (SecurityException e) {
            return errorResponse(403, "Forbidden", e.getMessage());
        } catch (IOException e) {
            return errorResponse(502, "Bad Gateway",
                    "Không phân giải được host đích: " + e.getMessage());
        }

        if (!allowBrowserRequest(clientAddress)) {
            return errorResponse(429, "Too Many Requests",
                    "Đã đạt giới hạn 30 request Browser mỗi phút từ địa chỉ này.");
        }
        log("Browser proxy " + method + " → " + target);
        URI current = target;
        String currentMethod = method;
        byte[] currentBody = requestBody;
        String currentContentType = contentType;

        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = null;
            try {
                validateBrowserTarget(current);
                URL url = current.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod(currentMethod);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Lab7-HTTP-Browser/1.0");
                connection.setRequestProperty("Connection", "close");
                if ("POST".equals(currentMethod)) {
                    String forwardedType = safeHeaderValue(currentContentType);
                    connection.setRequestProperty("Content-Type",
                            forwardedType.isEmpty()
                                    ? "application/x-www-form-urlencoded; charset=UTF-8"
                                    : forwardedType);
                    connection.setDoOutput(true);
                    byte[] body = currentBody == null ? new byte[0] : currentBody;
                    connection.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }
                }

                int status = connection.getResponseCode();
                String reason = connection.getResponseMessage();
                String location = connection.getHeaderField("Location");
                if (isRedirectStatus(status) && location != null) {
                    if (redirects == 5) {
                        return errorResponse(502, "Bad Gateway",
                                "Website vượt quá giới hạn 5 lần chuyển hướng.");
                    }
                    current = current.resolve(location);
                    validateBrowserTarget(current);
                    if (status == 303 || (status == 301 || status == 302)
                            && "POST".equals(currentMethod)) {
                        currentMethod = "GET";
                        currentBody = null;
                        currentContentType = null;
                    }
                    continue;
                }

                byte[] body = new byte[0];
                if (!"HEAD".equals(currentMethod) && status != 204 && status != 304) {
                    InputStream responseStream = status >= 400
                            ? connection.getErrorStream() : connection.getInputStream();
                    if (responseStream != null) {
                        try (InputStream input = responseStream) {
                            body = readBounded(input, MAX_BROWSER_RESPONSE_BYTES);
                        }
                    }
                }
                String responseType = safeHeaderValue(connection.getContentType());
                if (responseType.isEmpty()) {
                    responseType = "application/octet-stream";
                }
                Map<String, String> forwardedHeaders = new LinkedHashMap<>();
                copyResponseHeader(connection, forwardedHeaders, "Last-Modified");
                copyResponseHeader(connection, forwardedHeaders, "ETag");
                copyResponseHeader(connection, forwardedHeaders, "Cache-Control");
                copyResponseHeader(connection, forwardedHeaders, "Expires");
                copyResponseHeader(connection, forwardedHeaders, "Content-Length");
                long actualLength = connection.getContentLengthLong();
                if (!forwardedHeaders.containsKey("Content-Length") && actualLength >= 0) {
                    forwardedHeaders.put("Content-Length", String.valueOf(actualLength));
                } else if (!"HEAD".equals(method)) {
                    forwardedHeaders.put("Content-Length", String.valueOf(body.length));
                }
                return new FileResponse(status, reason == null ? "Response" : reason,
                        responseType, body, forwardedHeaders);
            } catch (SecurityException e) {
                return errorResponse(403, "Forbidden", e.getMessage());
            } catch (IOException e) {
                log("Browser proxy lỗi với " + current + ": " + e.getMessage());
                return errorResponse(502, "Bad Gateway",
                        "Không lấy được trang đích (" + e.getMessage() + ").");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return errorResponse(502, "Bad Gateway", "Không thể hoàn tất chuyển hướng.");
    }

    private FileResponse inspectUrl(String requestTarget, String clientAddress) {
        if (!allowBrowserRequest(clientAddress)) {
            return errorResponse(429, "Too Many Requests",
                    "Đã đạt giới hạn 30 request phân tích mỗi phút từ địa chỉ này.");
        }
        try {
            String rawQuery = URI.create(requestTarget).getRawQuery();
            String externalUrl = queryParameter(rawQuery, "url");
            if (externalUrl == null || externalUrl.isBlank()) {
                return errorResponse(400, "Bad Request", "Thiếu tham số url.");
            }
            URI target = URI.create(externalUrl);
            validateBrowserTarget(target);
            UrlInspector.Analysis result = UrlInspector.analyze(externalUrl);
            StringBuilder json = new StringBuilder("{\"protocol\":\"")
                    .append(jsonEscape(result.protocol)).append("\",\"host\":\"")
                    .append(jsonEscape(result.host)).append("\",\"port\":")
                    .append(result.port).append(",\"defaultPort\":")
                    .append(result.defaultPort).append(",\"effectivePort\":")
                    .append(result.effectivePort).append(",\"file\":\"")
                    .append(jsonEscape(result.file)).append("\",\"addresses\":[");
            for (int i = 0; i < result.addresses.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(jsonEscape(result.addresses.get(i))).append('"');
            }
            json.append("]}");
            return new FileResponse(200, "OK", "application/json; charset=UTF-8",
                    json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return errorResponse(400, "Bad Request", "URL đích không hợp lệ.");
        } catch (SecurityException e) {
            return errorResponse(403, "Forbidden", e.getMessage());
        } catch (IOException e) {
            return errorResponse(502, "Bad Gateway",
                    "Không phân giải được host đích: " + e.getMessage());
        }
    }

    private String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                return URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void validateBrowserTarget(URI target) throws IOException {
        if (target == null || !("http".equalsIgnoreCase(target.getScheme())
                || "https".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null || target.getRawUserInfo() != null
                || target.getRawFragment() != null) {
            throw new IllegalArgumentException("Chỉ chấp nhận URL http(s) không có user info/fragment.");
        }
        int port = target.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new SecurityException("Browser proxy chỉ hỗ trợ cổng công khai 80 và 443.");
        }
        String host = target.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || "localhost".equals(host)) {
            throw new SecurityException("Không cho phép truy cập host nội bộ.");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (!isPublicAddress(address)) {
                throw new SecurityException("Không cho phép truy cập địa chỉ IP nội bộ hoặc đặc biệt.");
            }
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0 && first != 10 && first != 127
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 0 || second == 168))
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 198 && (second == 18 || second == 19 || second == 51))
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        return address instanceof java.net.Inet6Address
                && (bytes[0] & 0xe0) == 0x20
                && !(bytes[0] == 0x20 && bytes[1] == 0x01
                        && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
    }

    private boolean allowBrowserRequest(String clientAddress) {
        long now = System.currentTimeMillis();
        if (++browserRequestCounter % 128 == 0) {
            browserRateWindows.entrySet().removeIf(entry -> now - entry.getValue().windowStarted > 120_000);
        }
        if (browserRateWindows.size() >= 10_000 && !browserRateWindows.containsKey(clientAddress)) {
            return false;
        }
        ProxyRateWindow window = browserRateWindows.computeIfAbsent(
                clientAddress, ignored -> new ProxyRateWindow(now));
        return window.tryAcquire(now);
    }

    private boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) {
                throw new IOException("Response trang vượt giới hạn 5 MB.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void copyResponseHeader(HttpURLConnection connection, Map<String, String> target,
            String name) {
        String value = safeHeaderValue(connection.getHeaderField(name));
        if (!value.isEmpty()) {
            target.put(name, value);
        }
    }

    private String safeHeaderValue(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return "";
        }
        return value.length() > 2048 ? value.substring(0, 2048) : value;
    }

    private static final class ProxyRateWindow {
        private long windowStarted;
        private int requests;

        private ProxyRateWindow(long windowStarted) {
            this.windowStarted = windowStarted;
        }

        private synchronized boolean tryAcquire(long now) {
            if (now - windowStarted >= 60_000) {
                windowStarted = now;
                requests = 0;
            }
            return ++requests <= MAX_BROWSER_REQUESTS_PER_MINUTE;
        }
    }

    private FileResponse healthResponse() {
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        String json = "{\"status\":\"ok\",\"service\":\"Lab7 HTTP Server\","
                + "\"uptimeSeconds\":" + uptimeSeconds
                + ",\"storage\":\"filesystem\","
                + "\"services\":[\"static-files\",\"file-transfer\",\"http-audit\",\"url-inspector\"]}";
        return new FileResponse(200, "OK", "application/json; charset=UTF-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private FileResponse auditResponse() throws IOException {
        AuditStore store = auditStore;
        if (store == null) {
            return errorResponse(503, "Service Unavailable",
                    "Kho lưu trữ nhật ký chưa sẵn sàng.");
        }
        return new FileResponse(200, "OK", "application/json; charset=UTF-8",
                store.recentEventsJson(200).getBytes(StandardCharsets.UTF_8));
    }

    private FileResponse findFile(String requestPath) throws IOException {
        if ("/__files".equals(requestPath)) {
            return listFiles();
        }
        Path candidate = documentRoot.resolve(requestPath.substring(1)).normalize();
        if (!candidate.startsWith(documentRoot)) {
            return errorResponse(403, "Forbidden", "Không được truy cập đường dẫn này.");
        }
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve("index.html");
        }
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            return errorResponse(404, "Not Found", "Không tìm thấy file: " + requestPath);
        }
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(documentRoot)) {
            return errorResponse(403, "Forbidden", "Không được truy cập file bên ngoài thư mục server.");
        }
        String contentType = Files.probeContentType(realCandidate);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        if ("text/html".equals(contentType) || contentType.startsWith("text/")) {
            contentType += "; charset=UTF-8";
        }
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Files.getLastModifiedTime(realCandidate).toInstant().atZone(ZoneOffset.UTC));
        return new FileResponse(200, "OK", contentType, Files.readAllBytes(realCandidate),
                Map.of("Last-Modified", lastModified));
    }

    private FileResponse listFiles() throws IOException {
        StringBuilder json = new StringBuilder("{\"files\":[");
        boolean first = true;
        try (java.util.stream.Stream<Path> files = Files.walk(documentRoot)) {
            java.util.Iterator<Path> iterator = files.filter(Files::isRegularFile)
                    .sorted().iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!first) {
                    json.append(',');
                }
                first = false;
                String path = "/" + documentRoot.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                json.append("{\"path\":\"").append(jsonEscape(path))
                        .append("\",\"name\":\"").append(jsonEscape(file.getFileName().toString()))
                        .append("\",\"size\":").append(Files.size(file)).append('}');
            }
        }
        json.append("]}");
        return new FileResponse(200, "OK", "application/json; charset=UTF-8",
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (Character.isISOControl(character)) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private FileResponse saveUpload(String encodedName, byte[] bytes) {
        if (encodedName == null || encodedName.trim().isEmpty()) {
            return errorResponse(400, "Bad Request", "Thiếu header X-File-Name.");
        }
        final String decodedName;
        try {
            decodedName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return errorResponse(400, "Bad Request", "Tên tệp không hợp lệ.");
        }
        if (decodedName.contains("/") || decodedName.contains("\\")
                || decodedName.equals(".") || decodedName.equals("..")
                || decodedName.indexOf('\0') >= 0) {
            return errorResponse(400, "Bad Request", "Chỉ chấp nhận tên tệp, không nhận đường dẫn.");
        }
        for (int i = 0; i < decodedName.length(); i++) {
            if (Character.isISOControl(decodedName.charAt(i))
                    || "<>:\"|?*".indexOf(decodedName.charAt(i)) >= 0) {
                return errorResponse(400, "Bad Request", "Tên tệp chứa ký tự không hợp lệ.");
            }
        }
        if (decodedName.endsWith(".") || decodedName.endsWith(" ")) {
            return errorResponse(400, "Bad Request", "Tên tệp không được kết thúc bằng dấu chấm hoặc khoảng trắng.");
        }
        Path uploadDirectory = documentRoot.resolve("uploads").normalize();
        try {
            Files.createDirectories(uploadDirectory);
            Path realUploadDirectory = uploadDirectory.toRealPath();
            if (!realUploadDirectory.startsWith(documentRoot)) {
                return errorResponse(403, "Forbidden",
                        "Thư mục uploads phải nằm bên trong document root.");
            }
            Path destination = realUploadDirectory.resolve(decodedName).normalize();
            if (!destination.startsWith(realUploadDirectory)) {
                return errorResponse(400, "Bad Request", "Đường dẫn tải lên không hợp lệ.");
            }
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String message = "Đã tải lên: /uploads/" + decodedName + " (" + bytes.length + " bytes)";
            log(message);
            return new FileResponse(201, "Created", "text/plain; charset=UTF-8",
                    message.getBytes(StandardCharsets.UTF_8));
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return errorResponse(409, "Conflict",
                    "Tệp uploads/" + decodedName + " đã tồn tại. Đổi tên tệp rồi thử lại.");
        } catch (IOException e) {
            log("Không thể lưu tệp tải lên: " + e.getMessage());
            return errorResponse(500, "Internal Server Error", "Không thể lưu tệp trên máy chủ.");
        }
    }

    private FileResponse replaceUpload(String requestPath, byte[] bytes) {
        String name = uploadPathName(requestPath);
        if (name == null) {
            return methodNotAllowed("PUT", requestPath);
        }
        try {
            Path uploadDirectory = documentRoot.resolve("uploads").normalize();
            Files.createDirectories(uploadDirectory);
            Path realUploadDirectory = uploadDirectory.toRealPath();
            if (!realUploadDirectory.startsWith(documentRoot)) {
                return errorResponse(403, "Forbidden", "Thư mục uploads nằm ngoài document root.");
            }
            Path destination = realUploadDirectory.resolve(name).normalize();
            if (!destination.startsWith(realUploadDirectory)) {
                return errorResponse(400, "Bad Request", "Đường dẫn không hợp lệ.");
            }
            boolean existed = Files.exists(destination);
            if (existed && (Files.isSymbolicLink(destination) || !Files.isRegularFile(destination)
                    || !destination.toRealPath().startsWith(realUploadDirectory))) {
                return errorResponse(403, "Forbidden", "Không thể ghi tệp bên ngoài uploads.");
            }
            Files.write(destination, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            String message = (existed ? "Đã thay thế: " : "Đã tạo: ") + requestPath;
            return new FileResponse(existed ? 200 : 201, existed ? "OK" : "Created",
                    "text/plain; charset=UTF-8", message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("Không thể ghi tệp PUT: " + e.getMessage());
            return errorResponse(500, "Internal Server Error", "Không thể ghi tệp trên máy chủ.");
        }
    }

    private FileResponse deleteUpload(String requestPath) {
        String name = uploadPathName(requestPath);
        if (name == null) {
            return methodNotAllowed("DELETE", requestPath);
        }
        try {
            Path uploadDirectory = documentRoot.resolve("uploads").normalize();
            if (!Files.isDirectory(uploadDirectory)) {
                return errorResponse(404, "Not Found", "Không tìm thấy tệp cần xóa.");
            }
            Path realUploadDirectory = uploadDirectory.toRealPath();
            if (!realUploadDirectory.startsWith(documentRoot)) {
                return errorResponse(403, "Forbidden", "Thư mục uploads nằm ngoài document root.");
            }
            Path candidate = realUploadDirectory.resolve(name).normalize();
            if (!candidate.startsWith(realUploadDirectory) || !Files.isRegularFile(candidate)) {
                return errorResponse(404, "Not Found", "Không tìm thấy tệp cần xóa.");
            }
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realUploadDirectory)) {
                return errorResponse(403, "Forbidden", "Không thể xóa tệp bên ngoài uploads.");
            }
            Files.delete(candidate);
            return new FileResponse(200, "OK", "text/plain; charset=UTF-8",
                    ("Đã xóa: " + requestPath).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("Không thể xóa tệp: " + e.getMessage());
            return errorResponse(500, "Internal Server Error", "Không thể xóa tệp trên máy chủ.");
        }
    }

    private String uploadPathName(String requestPath) {
        if (!requestPath.startsWith("/uploads/")) {
            return null;
        }
        String name = requestPath.substring("/uploads/".length());
        if (name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))
                    || "<>:\"|?*".indexOf(name.charAt(i)) >= 0) {
                return null;
            }
        }
        return name.endsWith(".") || name.endsWith(" ") ? null : name;
    }

    private byte[] readBody(InputStream in, long contentLength) throws IOException {
        byte[] body = new byte[(int) contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = in.read(body, offset, body.length - offset);
            if (count == -1) {
                throw new HttpProtocolException(400, "Bad Request",
                        "Client ngắt kết nối trước khi gửi đủ Content-Length.");
            }
            offset += count;
        }
        return body;
    }

    private FileResponse errorResponse(int statusCode, String reason, String message) {
        return new FileResponse(statusCode, reason, "text/plain; charset=UTF-8",
                message.getBytes(StandardCharsets.UTF_8));
    }

    private void writeResponse(BufferedOutputStream out, String protocol, int statusCode,
            String reason, String contentType, byte[] body, boolean headOnly,
            Map<String, String> extraHeaders) throws IOException {
        String responseDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC));
        StringBuilder headers = new StringBuilder()
                .append(protocol).append(' ').append(statusCode).append(' ')
                .append(reason).append("\r\n")
                .append("Server: Lab7-HTTP-Server\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Date: ").append(responseDate).append("\r\n")
                .append("Connection: close\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        headers.append("\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
        log("→ " + protocol + " " + statusCode + " " + reason);
        log("   Server: Lab7-HTTP-Server");
        log("   Content-Type: " + contentType);
        log("   Content-Length: " + body.length);
        log("   Date: " + responseDate);
        log("   Connection: close");
        log("   X-Content-Type-Options: nosniff");
        log("   Referrer-Policy: no-referrer");
        extraHeaders.forEach((name, value) -> log("   " + name + ": " + value));
    }

    private String requestPath(String target, String method) throws HttpProtocolException {
        if ("*".equals(target) && "OPTIONS".equals(method)) {
            return target;
        }
        if (!target.startsWith("/") || target.startsWith("//")) {
            throw new HttpProtocolException(400, "Bad Request",
                    "Request target phải là đường dẫn origin-form bắt đầu bằng /.");
        }
        try {
            URI uri = URI.create(target);
            if (uri.getRawFragment() != null) {
                throw new HttpProtocolException(400, "Bad Request",
                        "Request target không được chứa fragment.");
            }
            String decodedPath = uri.getPath();
            if (decodedPath == null || decodedPath.isEmpty()) {
                return "/";
            }
            return decodedPath.startsWith("/") ? decodedPath : "/" + decodedPath;
        } catch (IllegalArgumentException e) {
            throw new HttpProtocolException(400, "Bad Request",
                    "Request target không phải URI hợp lệ.");
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1 && value != '\n') {
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > 8192) {
                throw new HttpProtocolException(431, "Request Header Fields Too Large",
                        "Một dòng HTTP request vượt quá 8 KB.");
            }
        }
        return buffer.size() == 0 && value == -1 ? null
                : new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void log(String message) {
        ServerLogger currentLogger = logger;
        if (currentLogger != null) {
            currentLogger.log(message);
        }
    }

    private static final class FileResponse {
        private final int statusCode;
        private final String reason;
        private final String contentType;
        private final byte[] body;
        private final Map<String, String> headers;

        private FileResponse(int statusCode, String reason, String contentType, byte[] body) {
            this(statusCode, reason, contentType, body, Map.of());
        }

        private FileResponse(int statusCode, String reason, String contentType, byte[] body,
                Map<String, String> headers) {
            this.statusCode = statusCode;
            this.reason = reason;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
        }
    }

    private static final class HttpProtocolException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;
        private final String reason;

        private HttpProtocolException(int statusCode, String reason, String message) {
            super(message);
            this.statusCode = statusCode;
            this.reason = reason;
        }
    }
}
