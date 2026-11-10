package com.agroinsumos.backend.security;

import com.agroinsumos.backend.menu.MenuService;
import com.agroinsumos.backend.usuario.Nivel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Arrays;
import java.util.Set;

/**
 * Permisos por opción de menú (tabla detalle_opciones_menu), evaluados en el servidor.
 *
 * <p>Modelo de autorización en dos capas:
 * <ul>
 *   <li><b>Opción</b> (este servicio): a qué módulo accede el usuario, p.ej. VENTAS_POS, COMPRAS_REGISTRO.</li>
 *   <li><b>Rol</b> (nivel A/S/U/O): qué tan sensible es la operación, p.ej. anular exige SUPERVISOR.</li>
 * </ul>
 * Uso: {@code @PreAuthorize("@permisos.tiene('VENTAS_FACTURAS')")} o, combinado,
 * {@code @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('VENTAS_FACTURAS')")}.
 *
 * <p>Los permisos NO viajan en el JWT: se leen de la base una vez por petición, de modo que
 * retirar una opción a un usuario tiene efecto inmediato. El administrador tiene todas.
 */
@Component("permisos")
@RequiredArgsConstructor
public class PermisoService {

    private static final String CACHE = PermisoService.class.getName() + ".codigos";

    private final MenuService menus;

    /** true si el usuario tiene AL MENOS UNA de las opciones indicadas. */
    public boolean tiene(String... codigos) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) return false;
        AuthUser user = AuthUser.desde(jwt.getToken());
        if (user.nivel() == Nivel.ADMIN) return true;
        Set<String> mios = codigosDe(user.usuarioId());
        return Arrays.stream(codigos).anyMatch(mios::contains);
    }

    /** Igual que {@link #tiene}, pero lanza 403 (para validaciones programáticas). */
    public void exigir(String... codigos) {
        if (!tiene(codigos)) {
            throw new AccessDeniedException("Requiere la opción " + String.join(" o ", codigos));
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> codigosDe(Long usuarioId) {
        RequestAttributes req = RequestContextHolder.getRequestAttributes();
        if (req == null) return menus.codigosPermitidos(usuarioId);
        Object cache = req.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST);
        if (cache == null) {
            cache = menus.codigosPermitidos(usuarioId);
            req.setAttribute(CACHE, cache, RequestAttributes.SCOPE_REQUEST);
        }
        return (Set<String>) cache;
    }
}
