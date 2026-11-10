package com.agroinsumos.backend.security;

import com.agroinsumos.backend.usuario.Nivel;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Usuario autenticado, extraído del JWT. Reemplaza el estado global estático
 * de Ctrl_Parametros (setIdusuario, setIdcompany, setNivel...) del Swing,
 * que en un servidor multiusuario sería compartido entre peticiones.
 */
public record AuthUser(Long usuarioId, String username, Long companiaId, Nivel nivel) {

    public static AuthUser desde(Jwt jwt) {
        return new AuthUser(
                ((Number) jwt.getClaim(JwtClaims.USUARIO_ID)).longValue(),
                jwt.getSubject(),
                ((Number) jwt.getClaim(JwtClaims.COMPANIA_ID)).longValue(),
                Nivel.valueOf(jwt.getClaimAsStringList(JwtClaims.ROLES).getFirst()));
    }
}
