package server;

import core.HttpServerCore;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? parsePort(args[0])
                : parsePort(System.getenv().getOrDefault("PORT", "8080"));
        Path documentRoot = Path.of(args.length > 1 ? args[1]
                : System.getenv().getOrDefault("LAB7_ROOT", "www"))
                .toAbsolutePath().normalize();

        HttpServerCore server = new HttpServerCore();
        server.start(port, documentRoot,
                message -> System.out.println(message),
                request -> System.out.printf("%s %s %s -> %d (%d ms, %d bytes) from %s%n",
                        request.time, request.method, request.path, request.statusCode,
                        request.durationMillis, request.bytes, request.clientAddress));
        System.out.println("WRITE_TOKEN=" + server.getWriteToken());
        System.out.println("Dùng Ctrl+C để dừng server.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stopped.countDown();
        }, "http-server-shutdown"));
        stopped.await();
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port phải là số nguyên từ 1 đến 65535: "
                    + value, e);
        }
    }
}
