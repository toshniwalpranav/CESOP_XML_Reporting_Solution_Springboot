package com.example.cesop.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * When cesop.public-mode=true (profile "public") only POST /api/cesop/validate and the static
 * page are reachable, and validate calls are rate limited per client IP. Export and resubmission
 * endpoints answer 404 so a public demo cannot be used as a free generator.
 */
@Component
public class PublicModeFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 3_600_000L;

    private final boolean publicMode;
    private final int maxPerHour;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public PublicModeFilter(@Value("${cesop.public-mode:false}") boolean publicMode,
                            @Value("${cesop.rate-limit-per-hour:30}") int maxPerHour) {
        this.publicMode = publicMode;
        this.maxPerHour = maxPerHour;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!publicMode) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI();
        if (path.startsWith("/api/")) {
            if (!path.equals("/api/cesop/validate")) {
                json(res, 404, "{\"error\":\"not available in public demo\"}");
                return;
            }
            if ("POST".equals(req.getMethod()) && !allow(req.getRemoteAddr())) {
                res.setHeader("Retry-After", "3600");
                json(res, 429, "{\"error\":\"rate limit exceeded\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean allow(String ip) {
        if (hits.size() > 10_000) hits.clear(); // crude memory guard
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= maxPerHour) return false;
            q.addLast(now);
            return true;
        }
    }

    private static void json(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(body);
    }
}
