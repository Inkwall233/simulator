package com.skilllab.admin.ai;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiTargetPolicy {

    private final Set<String> allowedOrigins = new HashSet<String>();

    public AiTargetPolicy(@Value("${skilllab.ai.allowed-target-origins:}") String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.trim().isEmpty()) {
            return;
        }
        for (String configuredOrigin : configuredOrigins.split(",")) {
            String normalized = normalizeOrigin(configuredOrigin.trim(), true);
            allowedOrigins.add(normalized);
        }
    }

    public String validateTarget(String value) {
        String origin = normalizeOrigin(value, false);
        if (allowedOrigins.isEmpty() || !allowedOrigins.contains(origin)) {
            throw new IllegalArgumentException("Target origin is not in AI_ALLOWED_TARGET_ORIGINS");
        }
        return value;
    }

    public boolean allowsOrigin(String value) {
        try {
            return allowedOrigins.contains(normalizeOrigin(value, false));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeOrigin(String value, boolean requireOriginOnly) {
        try {
            URL url = new URL(value);
            String protocol = url.getProtocol().toLowerCase();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IllegalArgumentException("Only HTTP and HTTPS targets are allowed");
            }
            if (url.getUserInfo() != null || url.getRef() != null) {
                throw new IllegalArgumentException("URL credentials and fragments are not allowed");
            }
            if (requireOriginOnly && ((url.getPath() != null
                    && !url.getPath().isEmpty() && !"/".equals(url.getPath()))
                    || url.getQuery() != null)) {
                throw new IllegalArgumentException("Allowed targets must be origins without a path");
            }
            String host = url.getHost().toLowerCase();
            if (host.isEmpty()) {
                throw new IllegalArgumentException("Target URL must include a host");
            }
            int port = url.getPort();
            boolean defaultPort = port == -1
                    || ("http".equals(protocol) && port == 80)
                    || ("https".equals(protocol) && port == 443);
            return protocol + "://" + host + (defaultPort ? "" : ":" + port);
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Target URL is invalid", exception);
        }
    }
}
