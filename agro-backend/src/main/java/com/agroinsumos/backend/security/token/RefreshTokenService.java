package com.agroinsumos.backend.security.token;

import com.agroinsumos.backend.security.SecurityProperties;
import com.agroinsumos.backend.usuario.Usuario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens opacos con rotación: cada uso entrega uno nuevo y revoca el anterior.
 * Si se presenta un token ya revocado (posible robo), se revoca toda la familia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repo;
    private final SecurityProperties props;

    public record Emitido(String valor, Instant expiraEn) {}

    public record Rotado(Usuario usuario, Emitido nuevo) {}

    @Transactional
    public Emitido emitir(Usuario usuario, String ip, String userAgent) {
        return crear(usuario, UUID.randomUUID(), ip, userAgent);
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Rotado rotar(String valor, String ip, String userAgent) {
        Instant ahora = Instant.now();
        RefreshToken actual = repo.findByTokenHash(hash(valor))
                .orElseThrow(() -> new BadCredentialsException("Refresh token inválido"));
        if (actual.getRevocadoEn() != null) {
            log.warn("Reutilización de refresh token revocado (usuario {}). Revocando familia {}",
                    actual.getUsuario().getId(), actual.getFamilia());
            repo.revocarFamilia(actual.getFamilia(), ahora); // noRollbackFor: se confirma pese al 401
            throw new BadCredentialsException("Refresh token inválido");
        }
        if (!actual.isVigente(ahora) || !actual.getUsuario().isActivo()) {
            throw new BadCredentialsException("Refresh token inválido");
        }
        actual.setRevocadoEn(ahora);
        return new Rotado(actual.getUsuario(), crear(actual.getUsuario(), actual.getFamilia(), ip, userAgent));
    }

    @Transactional
    public void revocar(String valor) {
        repo.findByTokenHash(hash(valor)).ifPresent(t -> repo.revocarFamilia(t.getFamilia(), Instant.now()));
    }

    @Transactional
    public void revocarTodos(Long usuarioId) {
        repo.revocarDeUsuario(usuarioId, Instant.now());
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgar() {
        int n = repo.purgarExpirados(Instant.now().minus(Duration.ofDays(1)));
        if (n > 0) log.info("Purgados {} refresh tokens expirados", n);
    }

    private Emitido crear(Usuario usuario, UUID familia, String ip, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var t = new RefreshToken();
        t.setUsuario(usuario);
        t.setFamilia(familia);
        t.setTokenHash(hash(valor));
        t.setExpiraEn(Instant.now().plus(props.jwt().refreshTokenTtl()));
        t.setIp(ip);
        t.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())));
        repo.save(t);
        return new Emitido(valor, t.getExpiraEn());
    }

    static String hash(String valor) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
