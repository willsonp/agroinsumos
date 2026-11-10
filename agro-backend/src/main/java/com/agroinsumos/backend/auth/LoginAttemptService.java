package com.agroinsumos.backend.auth;

import com.agroinsumos.backend.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Bloqueo temporal tras N intentos fallidos (protección contra fuerza bruta). */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UsuarioRepository usuarios;

    @Value("${app.security.login.max-intentos:5}")
    private int maxIntentos;

    @Value("${app.security.login.bloqueo:15m}")
    private Duration bloqueo;

    @Transactional
    public void fallido(String username) {
        usuarios.findByUsernameIgnoreCase(username.trim()).ifPresent(u -> {
            u.setIntentosFallidos(u.getIntentosFallidos() + 1);
            if (u.getIntentosFallidos() >= maxIntentos) {
                u.setBloqueadoHasta(Instant.now().plus(bloqueo));
                u.setIntentosFallidos(0);
            }
        });
    }

    @Transactional
    public void exitoso(Long usuarioId) {
        usuarios.findById(usuarioId).ifPresent(u -> {
            u.setIntentosFallidos(0);
            u.setBloqueadoHasta(null);
            u.setUltimoAcceso(Instant.now());
        });
    }
}
