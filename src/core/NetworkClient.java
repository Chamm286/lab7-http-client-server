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

        List<InetAddress> addresses = resolveIpv4All(host);
        InetAddress address = addresses.get(0);
        String targetIp = joinIpv4(addresses);

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

    private static List<InetAddress> resolveIpv4All(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        List<InetAddress> ipv4 = new ArrayList<>();
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address && !ipv4.contains(address)) {
                ipv4.add(address);
            }
        }
        if (ipv4.isEmpty()) {
            throw new UnknownHostException(
                    "Host " + host + " không có bản ghi IPv4, không thể kết nối theo yêu cầu IPv4.");
        }
        return ipv4;
    }

    private static String joinIpv4(List<InetAddress> addresses) {
        List<String> ips = new ArrayList<>();
        for (InetAddress address : addresses) {
            String ip = address.getHostAddress();
            if (!ips.contains(ip)) {
                ips.add(ip);
            }
        }
        return String.join(", ", ips);
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
