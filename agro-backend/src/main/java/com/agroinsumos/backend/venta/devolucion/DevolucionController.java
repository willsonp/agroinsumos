package com.agroinsumos.backend.venta.devolucion;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Ventas")
@RestController
@RequestMapping("/api/v1/ventas/devoluciones")
@PreAuthorize("@permisos.tiene('VENTAS_FACTURAS')")
@RequiredArgsConstructor
public class DevolucionController {

    private final DevolucionService service;

    @Operation(summary = "Devolución parcial o total: reingresa inventario y emite Nota de Crédito electrónica (e-CF 34)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('VENTAS_FACTURAS')")
    public ResponseEntity<DevolucionService.Response> devolver(AuthUser user, @Valid @RequestBody DevolucionService.Request r) {
        return ResponseEntity.status(201).body(service.devolver(user.companiaId(), user, r));
    }

    @GetMapping
    public Page<DevolucionService.Response> listar(AuthUser user,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.listar(user.companiaId(), p);
    }

    @GetMapping("/{id}")
    public DevolucionService.Response obtener(AuthUser user, @PathVariable Long id) {
        return service.obtener(user.companiaId(), id);
    }
}
