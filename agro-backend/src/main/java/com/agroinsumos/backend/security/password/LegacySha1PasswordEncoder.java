package com.agroinsumos.backend.security.password;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifica las contraseñas heredadas del sistema Swing:
 * <pre>userpassw = hex(SHA-1(password + idUsuario))</pre>
 * (ver Convertir.Encriptar.convertToSHA1 y VentanaLogin.ingresar()).
 *
 * <p>Formato almacenado tras la migración de datos: {@code {sha1legacy}<idUsuario>$<hexSha1>}.
 * Solo verifica; nunca genera nuevos hashes. Al iniciar sesión correctamente,
 * Spring Security re-codifica la contraseña con BCrypt (UserDetailsPasswordService),
 * de modo que los hashes SHA-1 desaparecen progresivamente.
 *
 * <p>Se prueba UTF-8 y windows-1252 porque el legacy usaba String.getBytes() con el
 * charset por defecto de la JVM (normalmente cp1252 en Windows).
 */
public class LegacySha1PasswordEncoder implements PasswordEncoder {

    public static final String ID = "sha1legacy";
    private static final List<Charset> CHARSETS = List.of(StandardCharsets.UTF_8, Charset.forName("windows-1252"));

    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException("El esquema SHA-1 legacy es solo de lectura");
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        int sep = encodedPassword.indexOf('$');
        if (sep <= 0) return false;
        String salt = encodedPassword.substring(0, sep);
        byte[] esperado;
        try {
            esperado = HexFormat.of().parseHex(encodedPassword.substring(sep + 1).trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Charset cs : CHARSETS) {
            byte[] calculado = sha1((rawPassword + salt).getBytes(cs));
            if (MessageDigest.isEqual(esperado, calculado)) return true;
        }
        return false;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return true;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
