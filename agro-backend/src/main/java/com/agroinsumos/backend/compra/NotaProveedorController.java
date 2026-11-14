package com.agroinsumos.backend.compra;

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

@Tag(name = "Compras y Cuentas por Pagar")
@RestController
@RequestMapping("/api/v1/cxp/notas")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_NOTAS')")
@RequiredArgsConstructor
public class NotaProveedorController {

    private final NotaProveedorService service;

    @Operation(summary = "Nota de crédito (reduce la deuda; puede devolver mercancía) o de débito (la aumenta) de un suplidor")
    @PostMapping
    public ResponseEntity<NotaProveedorService.Response> registrar(AuthUser user, @Valid @RequestBody NotaProveedorService.Request r) {
        return ResponseEntity.status(201).body(service.registrar(user, r));
    }

    @GetMapping
    public Page<NotaProveedorService.Response> buscar(AuthUser user, @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) Long compraId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), proveedorId, compraId, p);
    }

    @GetMapping("/{id}")
    public NotaProveedorService.Response obtener(AuthUser user, @PathVariable Long id) { return service.obtener(user.companiaId(), id); }

    @GetMapping("/{id}/impresion")
    public NotaProveedorService.NotaImpresion impresion(AuthUser user, @PathVariable Long id) {
        return service.impresion(user.companiaId(), id);
    }

    @PostMapping("/{id}/anular")
    public NotaProveedorService.Response anular(AuthUser user, @PathVariable Long id, @Valid @RequestBody NotaProveedorService.AnularRequest r) {
        return service.anular(user, id, r.motivo());
    }
}
