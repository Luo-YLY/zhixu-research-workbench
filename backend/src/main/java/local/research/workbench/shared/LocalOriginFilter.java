package local.research.workbench.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local single-user boundary. This is not a replacement for authentication on a public server. */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "::1", "[::1]");
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!LOCAL_HOSTS.contains(req.getServerName().toLowerCase())) {
            reject(res); return;
        }
        String origin = req.getHeader("Origin");
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
        if (mutation && ("cross-site".equals(req.getHeader("Sec-Fetch-Site")) ||
                (origin != null && !sameOrigin(req, origin)))) {
            reject(res); return;
        }
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
        chain.doFilter(req, res);
    }
    private boolean sameOrigin(HttpServletRequest req, String origin) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return req.getScheme().equalsIgnoreCase(uri.getScheme()) &&
                    req.getServerName().equalsIgnoreCase(uri.getHost()) && req.getServerPort() == port;
        } catch (IllegalArgumentException e) { return false; }
    }
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"ORIGIN_FORBIDDEN\",\"message\":\"仅允许本机同源请求\"}");
    }
}
