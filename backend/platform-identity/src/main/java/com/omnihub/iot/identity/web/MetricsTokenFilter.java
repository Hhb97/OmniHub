package com.omnihub.iot.identity.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets the monitoring system (Prometheus) read /actuator/prometheus with a static bearer token
 * (iot.metrics.token / IOT_METRICS_TOKEN). The token grants nothing else; when it is empty the endpoint is closed.
 */
@Component
public class MetricsTokenFilter extends OncePerRequestFilter {

    static final String PATH = "/actuator/prometheus";

    private final String token;

    public MetricsTokenFilter(MetricsProperties props) {
        this.token = props.getToken();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!token.isEmpty() && header != null && header.startsWith("Bearer ")
                && MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("metrics",
                    null, List.of(new SimpleGrantedAuthority("METRICS"))));
        }
        chain.doFilter(request, response);
    }
}
