package com.agroinsumos.backend.common.crud;

import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.agroinsumos.backend.security.PermisoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints estándar de un catálogo. Lectura: cualquier usuario autenticado (los catálogos se
 * consultan desde muchas pantallas: POS, compras, cobros...). Escritura: quien tenga la opción
 * de menú del catálogo ({@link #permisoEscritura()}). Las subclases solo declaran @RequestMapping.
 */
public abstract class CrudController<Q, R> {

    private PermisoService permisos;

    @Autowired
    void setPermisos(PermisoService permisos) { this.permisos = permisos; }

    protected abstract CrudService<?, Q, R> service();

    /** Código de la opción de menú que permite crear/editar/desactivar en este catálogo. */
    protected abstract String permisoEscritura();

    @GetMapping
    public Page<R> listar(AuthUser user, @RequestParam(required = false) String q,
                          @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return service().listar(user.companiaId(), q, pageable);
    }

    @GetMapping("/{id}")
    public R obtener(AuthUser user, @PathVariable Long id) {
        return service().obtener(user.companiaId(), id);
    }

    @PostMapping
    public ResponseEntity<R> crear(AuthUser user, @Valid @RequestBody Q request) {
        permisos.exigir(permisoEscritura());
        return ResponseEntity.status(HttpStatus.CREATED).body(service().crear(user.companiaId(), request));
    }

    @PutMapping("/{id}")
    public R actualizar(AuthUser user, @PathVariable Long id, @Valid @RequestBody Q request) {
        permisos.exigir(permisoEscritura());
        return service().actualizar(user.companiaId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(AuthUser user, @PathVariable Long id) {
        permisos.exigir(permisoEscritura());
        service().desactivar(user.companiaId(), id);
        return ResponseEntity.noContent().build();
    }
}
