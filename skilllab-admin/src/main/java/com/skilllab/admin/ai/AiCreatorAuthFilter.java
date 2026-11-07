package com.skilllab.admin.ai;

import java.io.IOException;
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
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class AiCreatorAuthFilter extends OncePerRequestFilter {
    private final AiCreatorAuth auth;
    private final String allowedOrigin;

    public AiCreatorAuthFilter(AiCreatorAuth auth,
            @Value("${skilllab.ai.admin-origin:http://localhost:5174}") String allowedOrigin) {
        this.auth = auth;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/ai/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            String origin = request.getHeader("Origin");
            if (origin == null || !allowedOrigin.equals(origin)) {
                response.sendError(403, "Invalid creator request origin");
                return;
            }
        }
        if (!"/api/ai/auth/login".equals(path)) {
            try {
                auth.requireUser(request);
            } catch (SecurityException exception) {
                response.sendError(401, "Creator login required");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
