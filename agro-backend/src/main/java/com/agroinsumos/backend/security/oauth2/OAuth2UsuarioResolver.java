package com.agroinsumos.backend.security.oauth2;

import com.agroinsumos.backend.usuario.Usuario;
import com.agroinsumos.backend.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Asocia una identidad externa (Google, Microsoft...) a un usuario EXISTENTE del sistema.
 * Política de seguridad: no se auto-registran usuarios. Un administrador debe crear el
 * usuario con el mismo correo; el primer login social lo vincula automáticamente si el
 * proveedor declara el correo como verificado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2UsuarioResolver {

    private final IdentidadExternaRepository identidades;
    private final UsuarioRepository usuarios;

    @Transactional
    public Optional<Usuario> resolver(String proveedor, OAuth2User externo) {
        String sujeto = externo.getName();
        var vinculada = identidades.findByProveedorAndSujeto(proveedor, sujeto);
        if (vinculada.isPresent()) {
            return Optional.of(vinculada.get().getUsuario()).filter(Usuario::isActivo);
        }
        String correo = externo.getAttribute("email");
        if (correo == null || !correoVerificado(externo)) {
            log.warn("Login OAuth2 ({}) rechazado: correo ausente o no verificado", proveedor);
            return Optional.empty();
        }
        return usuarios.findByCorreoIgnoreCase(correo).filter(Usuario::isActivo).map(u -> {
            var id = new IdentidadExterna();
            id.setUsuario(u);
            id.setProveedor(proveedor);
            id.setSujeto(sujeto);
            id.setCorreo(correo);
            identidades.save(id);
            log.info("Usuario {} vinculado con {}", u.getId(), proveedor);
            return u;
        });
    }

    private static boolean correoVerificado(OAuth2User u) {
        if (u instanceof OidcUser oidc) {
            return Boolean.TRUE.equals(oidc.getEmailVerified());
        }
        Object v = u.getAttribute("email_verified");
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }
}
