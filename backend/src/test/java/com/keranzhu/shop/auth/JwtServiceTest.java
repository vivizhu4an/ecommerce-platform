package com.keranzhu.shop.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit test, no Spring context or containers. */
class JwtServiceTest {

    private static final String SECRET = "c2hvcC1kZW1vLXNlY3JldC1rZXktY2hhbmdlLW1lLXBsZWFzZS0yNTYtYml0cw==";
    private static final Instant NOW = Instant.parse("2025-07-01T12:00:00Z");

    private final JwtProperties props = new JwtProperties(SECRET, Duration.ofHours(2));

    @Test
    void issuedTokenVerifiesWithSameClaims() {
        JwtService jwt = new JwtService(props, Clock.fixed(NOW, ZoneOffset.UTC));
        User user = user(42L, Role.ADMIN);

        AuthPrincipal p = jwt.verify(jwt.issue(user)).orElseThrow();

        assertThat(p.userId()).isEqualTo(42L);
        assertThat(p.email()).isEqualTo("a@b.c");
        assertThat(p.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = new JwtService(props, Clock.fixed(NOW, ZoneOffset.UTC)).issue(user(1L, Role.USER));
        JwtService later = new JwtService(props, Clock.fixed(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC));

        assertThat(later.verify(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(props, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = jwt.issue(user(1L, Role.USER));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThat(jwt.verify(tampered)).isEmpty();
    }

    private static User user(long id, Role role) {
        User u = new User("a@b.c", "hash", role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }
}
