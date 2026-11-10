package com.agroinsumos.backend.usuario;

import com.agroinsumos.backend.menu.MenuService;
import com.agroinsumos.backend.menu.dto.MenuDtos.PermisosRequest;
import com.agroinsumos.backend.menu.dto.MenuDtos.PermisoOpcionResponse;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.usuario.dto.UsuarioDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/** Reemplaza FrmUsuario (Swing). Solo administradores (nivel 'A' en el legacy). */
@Tag(name = "Usuarios")
@RestController
@RequestMapping("/api/v1/usuarios")
// Solo ADMIN: un usuario con esta opción podría darse a sí mismo nivel ADMIN o más permisos
@PreAuthorize("hasRole('ADMIN') and @permisos.tiene('ADMIN_USUARIOS')")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService service;
    private final MenuService menuService;

    @GetMapping
    public Page<UsuarioResponse> listar(AuthUser user, @RequestParam(required = false) String q,
                                        @PageableDefault(size = 20, sort = "nombre") Pageable pageable) {
        return service.buscar(user.companiaId(), q, pageable);
    }

    @GetMapping("/{id}")
    public UsuarioResponse obtener(AuthUser user, @PathVariable Long id) {
        return service.obtener(user.companiaId(), id);
    }

    @PostMapping
    public ResponseEntity<UsuarioResponse> crear(AuthUser user, @Valid @RequestBody CrearUsuarioRequest req) {
        var creado = service.crear(user.companiaId(), req);
        return ResponseEntity.created(URI.create("/api/v1/usuarios/" + creado.id())).body(creado);
    }

    @PutMapping("/{id}")
    public UsuarioResponse actualizar(AuthUser user, @PathVariable Long id, @Valid @RequestBody UsuarioRequest req) {
        return service.actualizar(user.companiaId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(AuthUser user, @PathVariable Long id) {
        service.desactivar(user.companiaId(), id, user.usuarioId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(AuthUser user, @PathVariable Long id,
                                              @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(user.companiaId(), id, req.passwordNueva());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/permisos")
    public List<PermisoOpcionResponse> permisos(AuthUser user, @PathVariable Long id) {
        service.cargar(user.companiaId(), id);
        return menuService.permisosDe(id);
    }

    @PutMapping("/{id}/permisos")
    public List<PermisoOpcionResponse> asignarPermisos(AuthUser user, @PathVariable Long id,
                                                       @Valid @RequestBody PermisosRequest req) {
        var u = service.cargar(user.companiaId(), id);
        menuService.asignar(u, req.subMenuIds());
        return menuService.permisosDe(id);
    }
}
