package core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HttpClientCore {
    public static final class Response {
        public final int statusCode;
        public final String statusLine;
        public final String body;
        public final byte[] bodyBytes;
        public final Map<String, List<String>> headers;

        private Response(int statusCode, String statusLine, String body, byte[] bodyBytes,
                Map<String, List<String>> headers) {
            this.statusCode = statusCode;
            this.statusLine = statusLine;
            this.body = body;
            this.bodyBytes = bodyBytes;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    public static Response sendRequest(String urlString, String method) throws IOException {
        return sendRequest(urlString, method, Collections.emptyMap(), null);
    }

    public static Response sendRequest(String urlString, String method,
            Map<String, String> requestHeaders, byte[] requestBody) throws IOException {
        URI uri;
        try {
            uri = URI.create(urlString.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("URL không hợp lệ.", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            throw new IOException("URL phải có dạng http://host[:port]/path hoặc https://host[:port]/path.");
        }
        String normalizedMethod = method.toUpperCase(java.util.Locale.ROOT);
        if (!"GET".equals(normalizedMethod) && !"HEAD".equals(normalizedMethod)
                && !"POST".equals(normalizedMethod) && !"PUT".equals(normalizedMethod)
                && !"DELETE".equals(normalizedMethod) && !"OPTIONS".equals(normalizedMethod)) {
            throw new IOException("HTTP method chưa được hỗ trợ.");
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod(normalizedMethod);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Connection", "close");
        for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        try {
            if ("POST".equals(normalizedMethod) || "PUT".equals(normalizedMethod)) {
                byte[] body = requestBody == null ? new byte[0] : requestBody;
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int statusCode = connection.getResponseCode();
            String statusLine = connection.getHeaderField(0);
            if (statusLine == null) {
                statusLine = "HTTP/1.1 " + statusCode + " " + connection.getResponseMessage();
            }
            byte[] bodyBytes = new byte[0];
            if (!"HEAD".equals(normalizedMethod)) {
                InputStream responseStream = statusCode >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                if (responseStream != null) {
                    try (InputStream input = responseStream;
                            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                        bodyBytes = output.toByteArray();
                    }
                }
            }
            String body = new String(bodyBytes, responseCharset(connection.getContentType()));
            return new Response(statusCode, statusLine, body, bodyBytes,
                    connection.getHeaderFields());
        } finally {
            connection.disconnect();
        }
    }

    private static Charset responseCharset(String contentType) {
        if (contentType != null) {
            for (String parameter : contentType.split(";")) {
                String trimmed = parameter.trim();
                if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
                    String name = trimmed.substring("charset=".length()).replace("\"", "");
                    try {
                        return Charset.forName(name);
                    } catch (IllegalArgumentException ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
