package com.omnihub.iot.identity.core;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public record Parsed(long userId, String jti, Instant expiresAt) {
    }

    private final SecurityProperties props;
    private SecretKey key;
    /** jti -> expiry. Tokens revoked by logout; entries are dropped once the token would have expired anyway. */
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public JwtService(SecurityProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String secret = props.getJwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "iot.security.jwt-secret (env IOT_JWT_SECRET) must be set and at least 32 bytes long");
        }
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public long ttlSeconds() {
        return Duration.ofMinutes(props.getJwtTtlMinutes()).toSeconds();
    }

    public String issue(long userId, long tenantId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(String.valueOf(userId))
                .claim("tid", tenantId).claim("name", username).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds()))).signWith(key).compact();
    }

    public Optional<Parsed> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (revoked.containsKey(c.getId())) {
                return Optional.empty();
            }
            return Optional.of(new Parsed(Long.parseLong(c.getSubject()), c.getId(), c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void revoke(String token) {
        parse(token).ifPresent(p -> {
            revoked.put(p.jti(), p.expiresAt());
            Instant now = Instant.now();
            revoked.values().removeIf(exp -> exp.isBefore(now));
        });
    }
}
