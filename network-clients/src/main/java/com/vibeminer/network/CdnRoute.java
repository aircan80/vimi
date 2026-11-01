package com.vibeminer.network;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Official CDN hosts in advertised order; arbitrary URLs and traversal paths are refused. */
public record CdnRoute(List<String> hosts, String path) {
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_-]{1,32}(?:/[a-z0-9_-]{1,64}){1,3}");
    private static final Pattern HOST = Pattern.compile("[a-z]{2,8}\\.cdn\\.blizzard\\.com");

    public CdnRoute {
        Objects.requireNonNull(hosts);
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("A CDN path is like tpr/wow");
        }
        LinkedHashSet<String> admitted = new LinkedHashSet<>();
        for (String host : hosts) {
            if (isOfficialHost(host)) {
                admitted.add(host);
            }
        }
        if (admitted.isEmpty() || admitted.size() > 16) {
            throw new IllegalArgumentException("A CDN route needs 1–16 official hosts");
        }
        hosts = List.copyOf(admitted);
    }

    public static boolean isOfficialHost(String host) {
        return host != null
                && (host.equals("level3.blizzard.com")
                        || host.equals("level3.ssl.blizzard.com")
                        || HOST.matcher(host).matches());
    }

    public CdnRoute withHosts(List<String> order) {
        return new CdnRoute(order, path);
    }

    public URI urlFor(String host, NetworkTypes.ObjectKind kind, CdnKey key) {
        if (!hosts.contains(host)) {
            throw new IllegalArgumentException("Host is not in this route");
        }
        Objects.requireNonNull(kind);
        String hex = key.hex();
        return URI.create(
                "https://"
                        + host
                        + "/"
                        + path
                        + "/"
                        + (kind == NetworkTypes.ObjectKind.CONFIG ? "config" : "data")
                        + "/"
                        + hex.substring(0, 2)
                        + "/"
                        + hex.substring(2, 4)
                        + "/"
                        + hex
                        + (kind == NetworkTypes.ObjectKind.INDEX ? ".index" : ""));
    }
}
