package com.agroinsumos.backend.auth;

import com.agroinsumos.backend.auth.dto.AuthDtos.*;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.menu.MenuService;
import com.agroinsumos.backend.security.jwt.JwtTokenService;
import com.agroinsumos.backend.security.token.RefreshTokenService;
import com.agroinsumos.backend.usuario.Nivel;
import com.agroinsumos.backend.usuario.Usuario;
import com.agroinsumos.backend.usuario.UsuarioRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Lógica extraída de VentanaLogin.ingresar(). Corrige del legacy:
 * SQL concatenado (inyección SQL), SHA-1 sin sal, y estado de sesión en variables estáticas.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService intentos;
    private final UsuarioRepository usuarios;
    private final JwtTokenService jwt;
    private final RefreshTokenService refreshTokens;
    private final MenuService menus;

    public TokenResponse login(LoginRequest req, HttpServletRequest http) {
        intentos.verificarIp(ip(http));
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.username().trim(), req.password()));
        } catch (BadCredentialsException e) {
            intentos.fallido(req.username(), ip(http));
            throw e;
        }
        Usuario u = usuarios.findByUsernameIgnoreCase(req.username().trim()).orElseThrow();
        intentos.exitoso(u.getId(), ip(http));
        return emitirTokens(u, http);
    }

    public TokenResponse refresh(RefreshRequest req, HttpServletRequest http) {
        var rotado = refreshTokens.rotar(req.refreshToken(), ip(http), http.getHeader("User-Agent"));
        return respuesta(jwt.emitir(rotado.usuario()), rotado.nuevo());
    }

    public void logout(RefreshRequest req) {
        refreshTokens.revocar(req.refreshToken());
    }

    public TokenResponse emitirTokens(Usuario u, HttpServletRequest http) {
        var refresh = refreshTokens.emitir(u, ip(http), http.getHeader("User-Agent"));
        return respuesta(jwt.emitir(u), refresh);
    }

    @Transactional(readOnly = true)
    public PerfilResponse perfil(Long usuarioId) {
        Usuario u = usuarios.findWithCompaniaById(usuarioId).orElseThrow(() -> new NotFoundException("Usuario", usuarioId));
        var c = u.getCompania();
        Set<String> permisos = u.getNivel() == Nivel.ADMIN ? Set.of("*") : menus.codigosPermitidos(usuarioId);
        return new PerfilResponse(u.getId(), u.getUsername(), u.nombreCompleto(), u.getCorreo(), u.getNivel(),
                u.getTurnoId(),
                new CompaniaInfo(c.getId(), c.getNombre(), c.getRnc(), c.getDireccion(), c.getCorreo(),
                        c.getSitioWeb(), c.getItbis(), c.isAceptaContabilidad()),
                permisos);
    }

    private static TokenResponse respuesta(JwtTokenService.AccessToken access, RefreshTokenService.Emitido refresh) {
        Instant ahora = Instant.now();
        return new TokenResponse(access.valor(), "Bearer",
                Duration.between(ahora, access.expiraEn()).toSeconds(),
                refresh.valor(), Duration.between(ahora, refresh.expiraEn()).toSeconds());
    }

    private static String ip(HttpServletRequest http) {
        return http.getRemoteAddr();
    }
}
