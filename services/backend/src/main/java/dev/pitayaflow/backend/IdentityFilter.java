package dev.pitayaflow.backend;

// Adapted from LinkVideo f5451c4, server/config/AuthInterceptor.java (MIT, Copyright 2026 Majst).

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class IdentityFilter extends OncePerRequestFilter {
    private final IdentityService identity;
    IdentityFilter(IdentityService identity) { this.identity = identity; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.equals("/") || path.equals("/index.html") || path.equals("/media-auth-sw.js") || path.startsWith("/assets/") ||
                path.equals("/actuator/health") || path.equals("/api/v1/login") || path.equals("/api/v1/bootstrap") || path.startsWith("/internal/agent/")) { chain.doFilter(req,res); return; }
        if (!path.startsWith("/api/v1/")) { res.sendError(404); return; }
        try { req.setAttribute("pf_user_id", identity.resolve(req.getHeader("Authorization"))); chain.doFilter(req,res); }
        catch (ApiError e) { res.sendError(e.getStatusCode().value(), e.getReason()); }
    }
}
