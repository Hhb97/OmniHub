package com.omnihub.iot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnihub.iot.config.IotProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** Token issuing / parsing rules: signature, expiry, revocation and secret strength. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";

    private static JwtService service(String secret, int ttlMinutes) {
        IotProperties props = new IotProperties();
        props.getSecurity().setJwtSecret(secret);
        props.getSecurity().setJwtTtlMinutes(ttlMinutes);
        JwtService s = new JwtService(props);
        s.init();
        return s;
    }

    @Test
    void issuedTokenParsesBackToTheSameUser() {
        JwtService jwt = service(SECRET, 30);
        String token = jwt.issue(42L, 7L, "alice");

        var parsed = jwt.parse(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().userId()).isEqualTo(42L);
        assertThat(parsed.get().jti()).isNotBlank();
        assertThat(parsed.get().expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(29)),
                Instant.now().plus(Duration.ofMinutes(31)));
    }

    @Test
    void everyTokenGetsItsOwnId() {
        JwtService jwt = service(SECRET, 30);
        assertThat(jwt.parse(jwt.issue(1L, 1L, "a")).orElseThrow().jti())
                .isNotEqualTo(jwt.parse(jwt.issue(1L, 1L, "a")).orElseThrow().jti());
    }

    @Test
    void ttlIsReportedInSeconds() {
        assertThat(service(SECRET, 120).ttlSeconds()).isEqualTo(7200);
    }

    @Test
    void tamperedTokensAreRejected() {
        JwtService jwt = service(SECRET, 30);
        String token = jwt.issue(1L, 1L, "alice");
        String[] parts = token.split("\\.");

        // signature changed
        char last = token.charAt(token.length() - 1);
        String badSignature = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(jwt.parse(badSignature)).isEmpty();

        // payload swapped for another user's, signature kept
        String otherPayload = jwt.issue(99L, 1L, "mallory").split("\\.")[1];
        assertThat(jwt.parse(parts[0] + "." + otherPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = service("another-secret-0123456789-0123456789-xx", 30).issue(1L, 1L, "alice");
        assertThat(service(SECRET, 30).parse(foreign)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = service(SECRET, -1);
        assertThat(jwt.parse(jwt.issue(1L, 1L, "alice"))).isEmpty();
    }

    @Test
    void unsignedTokenWithAlgNoneIsRejected() {
        String unsigned = Jwts.builder().subject("1").expiration(Date.from(Instant.now().plusSeconds(600))).compact();
        assertThat(service(SECRET, 30).parse(unsigned)).isEmpty();
    }

    @Test
    void tokenSignedWithAWeakerAlgorithmFamilyStillNeedsTheRightKey() {
        var otherKey = Keys.hmacShaKeyFor("some-other-key-0123456789-0123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().subject("1").id("x").expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(otherKey).compact();
        assertThat(service(SECRET, 30).parse(forged)).isEmpty();
    }

    @Test
    void garbageNeverThrows() {
        JwtService jwt = service(SECRET, 30);
        assertThat(jwt.parse(null)).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
        assertThat(jwt.parse("   ")).isEmpty();
        assertThat(jwt.parse("not-a-jwt")).isEmpty();
        assertThat(jwt.parse("a.b.c")).isEmpty();
        assertThat(jwt.parse("Bearer " + jwt.issue(1L, 1L, "x"))).isEmpty();
    }

    @Test
    void revokedTokenStopsWorkingButOthersKeepWorking() {
        JwtService jwt = service(SECRET, 30);
        String a = jwt.issue(1L, 1L, "alice");
        String b = jwt.issue(1L, 1L, "alice");
        assertThat(jwt.parse(a)).isPresent();

        jwt.revoke(a);

        assertThat(jwt.parse(a)).isEmpty();
        assertThat(jwt.parse(b)).isPresent();
        // revoking twice or revoking garbage is harmless
        jwt.revoke(a);
        jwt.revoke("garbage");
        jwt.revoke(null);
        assertThat(jwt.parse(b)).isPresent();
    }

    @Test
    void secretMustBeAtLeast32Bytes() {
        assertThatThrownBy(() -> service("x".repeat(31), 30)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> service("", 30)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(null, 30)).isInstanceOf(IllegalStateException.class);
        // exactly 32 bytes is fine; the limit counts bytes, not characters
        assertThat(service("x".repeat(32), 30).issue(1L, 1L, "a")).isNotBlank();
        assertThat(service("密钥".repeat(6) + "ab", 30).issue(1L, 1L, "a")).isNotBlank();
    }
}
