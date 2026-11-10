package com.agroinsumos.backend.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(Jwt jwt, Cors cors, OAuth2 oauth2) {

    public record Jwt(String secret, @NotBlank String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {
        public Jwt {
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "app.security.jwt.secret (JWT_SECRET) es obligatorio y debe tener al menos 32 bytes");
            }
        }
    }

    public record Cors(List<String> allowedOrigins) {}

    public record OAuth2(String authorizedRedirectUri) {}
}
