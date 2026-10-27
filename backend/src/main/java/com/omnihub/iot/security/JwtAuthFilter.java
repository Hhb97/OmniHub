package com.omnihub.iot.security;

import com.omnihub.iot.system.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AccountService accountService;

    public JwtAuthFilter(JwtService jwtService, AccountService accountService) {
        this.jwtService = jwtService;
        this.accountService = accountService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authenticate(header.substring(7));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        Optional<JwtService.Parsed> parsed = jwtService.parse(token);
        if (parsed.isEmpty()) {
            return;
        }
        // permissions are re-read on every request so role changes / disabling a user take effect immediately
        accountService.loadLoginUser(parsed.get().userId()).ifPresent(user -> {
            var authorities = user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            var auth = new UsernamePasswordAuthenticationToken(user, token, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
    }
}
