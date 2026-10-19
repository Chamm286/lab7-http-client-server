package core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class AuditStore {
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private final Path file;
    private final Path previousFile;

    AuditStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("audit.jsonl");
        previousFile = directory.resolve("audit.previous.jsonl");
    }

    synchronized void append(String client, String method, String path, int status,
            long durationMillis, long bytesSent) throws IOException {
        if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
            Files.move(file, previousFile, StandardCopyOption.REPLACE_EXISTING);
        }
        String record = "{\"time\":\"" + escape(Instant.now().toString())
                + "\",\"client\":\"" + escape(client)
                + "\",\"method\":\"" + escape(method)
                + "\",\"path\":\"" + escape(path)
                + "\",\"status\":" + status
                + ",\"durationMillis\":" + durationMillis
                + ",\"bytesSent\":" + bytesSent + "}\n";
        Files.writeString(file, record, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    synchronized String recentEventsJson(int limit) throws IOException {
        List<String> records = new ArrayList<>(limit);
        appendRecent(previousFile, records, limit);
        appendRecent(file, records, limit);
        int first = Math.max(0, records.size() - limit);
        return "{\"events\":[" + String.join(",", records.subList(first, records.size())) + "]}";
    }

    private void appendRecent(Path source, List<String> records, int limit) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(line);
                if (records.size() > limit * 2) {
                    records.subList(0, records.size() - limit).clear();
                }
            }
        }
    }

    private static String escape(String value) {
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
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}
