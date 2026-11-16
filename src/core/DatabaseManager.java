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
