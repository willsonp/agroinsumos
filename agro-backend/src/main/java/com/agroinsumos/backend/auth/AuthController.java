package com.agroinsumos.backend.auth;

import com.agroinsumos.backend.auth.dto.AuthDtos.*;
import com.agroinsumos.backend.menu.MenuService;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.usuario.UsuarioService;
import com.agroinsumos.backend.usuario.dto.UsuarioDtos.CambiarPasswordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final MenuService menus;
    private final UsuarioService usuarios;
    private final ObjectProvider<ClientRegistrationRepository> registros;

    public AuthController(AuthService auth, MenuService menus, UsuarioService usuarios,
                          ObjectProvider<ClientRegistrationRepository> registros) {
        this.auth = auth;
        this.menus = menus;
        this.usuarios = usuarios;
        this.registros = registros;
    }

    @Operation(summary = "Login con usuario y contraseña. Devuelve JWT (corto) + refresh token (rotativo)")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req, http);
    }

    @Operation(summary = "Renueva el access token. El refresh token usado queda invalidado")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        return auth.refresh(req, http);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Perfil del usuario, datos de la compañía y permisos")
    @GetMapping("/me")
    public PerfilResponse me(AuthUser user) {
        return auth.perfil(user.usuarioId());
    }

    @Operation(summary = "Menú (módulos y opciones) permitido al usuario")
    @GetMapping("/menu")
    public MenuResponse menu(AuthUser user) {
        return new MenuResponse(menus.menuDe(user.usuarioId(), user.nivel()));
    }

    @PutMapping("/password")
    public ResponseEntity<Void> cambiarPassword(AuthUser user, @Valid @RequestBody CambiarPasswordRequest req) {
        usuarios.cambiarPassword(user.usuarioId(), req);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Proveedores de login social disponibles (redirigir el navegador a 'url')")
    @GetMapping("/oauth2/proveedores")
    public List<ProveedorOAuth2> proveedores() {
        List<ProveedorOAuth2> lista = new ArrayList<>();
        if (registros.getIfAvailable() instanceof Iterable<?> it) {
            for (Object o : it) {
                var reg = (ClientRegistration) o;
                lista.add(new ProveedorOAuth2(reg.getRegistrationId(), "/oauth2/authorization/" + reg.getRegistrationId()));
            }
        }
        return lista;
    }
}
