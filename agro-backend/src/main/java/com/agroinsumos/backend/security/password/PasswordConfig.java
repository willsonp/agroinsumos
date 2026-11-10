package com.agroinsumos.backend.security.password;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        var delegating = new DelegatingPasswordEncoder("bcrypt", Map.of(
                "bcrypt", new BCryptPasswordEncoder(12),
                LegacySha1PasswordEncoder.ID, new LegacySha1PasswordEncoder()));
        // Hash nulo, desconocido o sin prefijo (p.ej. usuario solo-OAuth2): nunca coincide.
        delegating.setDefaultPasswordEncoderForMatches(new RechazarTodoPasswordEncoder());
        return delegating;
    }

    private static final class RechazarTodoPasswordEncoder implements PasswordEncoder {
        @Override public String encode(CharSequence raw) { throw new UnsupportedOperationException(); }
        @Override public boolean matches(CharSequence raw, String encoded) { return false; }
    }
}
