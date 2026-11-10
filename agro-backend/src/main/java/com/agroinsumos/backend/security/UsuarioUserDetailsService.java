package com.agroinsumos.backend.security;

import com.agroinsumos.backend.usuario.Usuario;
import com.agroinsumos.backend.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UsuarioRepository usuarios;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        Usuario u = usuarios.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash() == null ? "{deshabilitado}" : u.getPasswordHash())
                .roles(u.getNivel().rol())
                .disabled(!u.isActivo())
                .accountLocked(u.isBloqueado(Instant.now()))
                .build();
    }

    /** Invocado por Spring Security cuando un hash legacy (SHA-1) se valida: se migra a BCrypt. */
    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        usuarios.findByUsernameIgnoreCase(user.getUsername()).ifPresent(u -> {
            u.setPasswordHash(newPassword);
            log.info("Contraseña del usuario {} migrada a BCrypt", u.getId());
        });
        return User.withUserDetails(user).password(newPassword).build();
    }
}
