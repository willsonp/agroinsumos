package com.agroinsumos.backend.auth;

import com.agroinsumos.backend.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.agroinsumos.backend.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Protección contra fuerza bruta (OWASP ASVS V2.2): bloqueo temporal del usuario tras N intentos fallidos y,
 * además, freno por dirección IP para que un atacante no pueda probar muchos usuarios ni bloquearlos a todos.
 * Cada intento fallido y cada bloqueo quedan en el log de seguridad (usuario, IP).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UsuarioRepository usuarios;

    /** Intentos fallidos recientes por IP (en memoria; se reinicia con el servidor). */
    private final Map<String, Deque<Instant>> porIp = new ConcurrentHashMap<>();

    @Value("${app.security.login.max-intentos-ip:20}")
    private int maxIntentosIp;

    @Value("${app.security.login.ventana-ip:15m}")
    private Duration ventanaIp;

    @Value("${app.security.login.max-intentos:5}")
    private int maxIntentos;

    @Value("${app.security.login.bloqueo:15m}")
    private Duration bloqueo;

    /** Rechaza (429) si la IP superó los intentos fallidos permitidos en la ventana. */
    public void verificarIp(String ip) {
        var lista = porIp.get(ip);
        if (lista == null) return;
        synchronized (lista) {
            depurar(lista);
            if (lista.size() >= maxIntentosIp) {
                log.warn("SEGURIDAD login rechazado por exceso de intentos desde la IP {}", ip);
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS",
                        "Demasiados intentos fallidos desde este equipo. Espere unos minutos e intente de nuevo.");
            }
        }
    }

    @Transactional
    public void fallido(String username, String ip) {
        var lista = porIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (lista) {
            depurar(lista);
            lista.addLast(Instant.now());
        }
        String nombre = username == null ? "" : username.trim();
        log.warn("SEGURIDAD login fallido usuario='{}' ip={}", nombre.replaceAll("[\\r\\n]", "_"), ip);
        usuarios.findByUsernameIgnoreCase(nombre).ifPresent(u -> {
            u.setIntentosFallidos(u.getIntentosFallidos() + 1);
            if (u.getIntentosFallidos() >= maxIntentos) {
                u.setBloqueadoHasta(Instant.now().plus(bloqueo));
                u.setIntentosFallidos(0);
                log.warn("SEGURIDAD usuario {} bloqueado {} por {} intentos fallidos (última IP {})", u.getId(), bloqueo, maxIntentos, ip);
            }
        });
    }

    private void depurar(Deque<Instant> lista) {
        Instant limite = Instant.now().minus(ventanaIp);
        while (!lista.isEmpty() && lista.peekFirst().isBefore(limite)) lista.pollFirst();
    }

    /** Limpia las IPs sin intentos recientes (evita que el mapa crezca sin límite). */
    @Scheduled(fixedDelay = 600_000)
    public void purgar() {
        porIp.entrySet().removeIf(e -> { synchronized (e.getValue()) { depurar(e.getValue()); return e.getValue().isEmpty(); } });
    }

    @Transactional
    public void exitoso(Long usuarioId, String ip) {
        log.info("SEGURIDAD login correcto usuario {} ip={}", usuarioId, ip);
        usuarios.findById(usuarioId).ifPresent(u -> {
            u.setIntentosFallidos(0);
            u.setBloqueadoHasta(null);
            u.setUltimoAcceso(Instant.now());
        });
    }
}
