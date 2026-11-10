package com.agroinsumos.backend.security;

import com.agroinsumos.backend.security.password.LegacySha1PasswordEncoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySha1PasswordEncoderTest {

    private final LegacySha1PasswordEncoder encoder = new LegacySha1PasswordEncoder();

    @Test
    void verificaHashGeneradoComoEnElSwing() {
        // Convertir.Encriptar.convertToSHA1("1234" + "3") == sha1("12343")
        String legacy = "3$" + "a2b0a0c1e9d08dd6c6dfd4b8d8a9be1fd0b2c0a2"; // valor ficticio -> no coincide
        assertThat(encoder.matches("1234", legacy)).isFalse();
        String real = "3$" + sha1("12343");
        assertThat(encoder.matches("1234", real)).isTrue();
        assertThat(encoder.matches("1234", real.toUpperCase())).isTrue();
        assertThat(encoder.matches("12345", real)).isFalse();
    }

    @Test
    void soportaCharsetWindows1252DelLegacy() throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest("contraseña7".getBytes("windows-1252")));
        assertThat(encoder.matches("contraseña", "7$" + hash)).isTrue();
    }

    @Test
    void formatoInvalidoNoCoincide() {
        assertThat(encoder.matches("x", "sinseparador")).isFalse();
        assertThat(encoder.matches("x", "1$zz")).isFalse();
    }

    private static String sha1(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
