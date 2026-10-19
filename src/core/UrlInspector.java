package core;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses URL components and resolves its host through the system DNS resolver. */
public final class UrlInspector {
    private UrlInspector() { }

    public static final class Analysis {
        public final String protocol;
        public final String host;
        public final int port;
        public final int defaultPort;
        public final int effectivePort;
        public final String file;
        public final String path;
        public final String query;
        public final List<String> addresses;
        public final String dnsError;

        private Analysis(URL url, List<String> addresses, String dnsError) {
            protocol = url.getProtocol();
            host = url.getHost();
            port = url.getPort();
            defaultPort = url.getDefaultPort();
            effectivePort = port >= 0 ? port : defaultPort;
            file = url.getFile();
            path = url.getPath();
            query = url.getQuery();
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            this.dnsError = dnsError;
        }
    }

    public static Analysis analyze(String address) throws MalformedURLException {
        if (address == null || address.trim().isEmpty()) {
            throw new MalformedURLException("Vui lòng nhập URL đầy đủ.");
        }

        URL url;
        try {
            url = URI.create(address.trim()).toURL();
        } catch (IllegalArgumentException e) {
            MalformedURLException invalid = new MalformedURLException("URL không hợp lệ: "
                    + e.getMessage());
            invalid.initCause(e);
            throw invalid;
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new MalformedURLException("URL phải có hostname, ví dụ https://vku.udn.vn/.");
        }

        List<String> resolved = new ArrayList<>();
        String dnsError = null;
        try {
            for (InetAddress inetAddress : InetAddress.getAllByName(url.getHost())) {
                resolved.add(inetAddress.getHostName() + "  —  " + inetAddress.getHostAddress());
            }
        } catch (UnknownHostException e) {
            dnsError = "Không phân giải được hostname: " + e.getMessage();
        }
        return new Analysis(url, resolved, dnsError);
    }
}
