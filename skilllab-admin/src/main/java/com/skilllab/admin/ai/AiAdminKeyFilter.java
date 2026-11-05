package com.skilllab.admin.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AiAdminKeyFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final String adminKey;

    public AiAdminKeyFilter(
            @Value("${skilllab.ai.enabled:true}") boolean enabled,
            @Value("${skilllab.ai.admin-key:}") String adminKey) {
        this.enabled = enabled;
        this.adminKey = adminKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/ai/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (adminKey == null || adminKey.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AI workbench is disabled until SKILLLAB_AI_ADMIN_KEY is configured");
            return;
        }
        String suppliedKey = request.getHeader("X-SkillLab-AI-Key");
        if (suppliedKey == null || !MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate", "SkillLab-AI-Key");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "AI workbench key required");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
