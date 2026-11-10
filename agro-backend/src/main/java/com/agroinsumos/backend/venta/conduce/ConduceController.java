package com.agroinsumos.backend.venta.conduce;

import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.factura.FacturaDtos;
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
@RequestMapping("/api/v1/ventas/conduces")
@RequiredArgsConstructor
public class ConduceController {

    private final ConduceService service;

    @Operation(summary = "Conduce: entrega de mercancía sin factura (sale del inventario)")
    @PostMapping
    @PreAuthorize("@permisos.tiene('VENTAS_CONDUCES')")
    public ResponseEntity<ConduceService.Response> crear(AuthUser user, @Valid @RequestBody ConduceService.Request r) {
        return ResponseEntity.status(201).body(service.crear(user, r));
    }

    @GetMapping
    @PreAuthorize("@permisos.tiene('VENTAS_CONDUCES')")
    public Page<ConduceService.Response> buscar(AuthUser user, @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Conduce.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), clienteId, estado, p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permisos.tiene('VENTAS_CONDUCES')")
    public ConduceService.Response obtener(AuthUser user, @PathVariable Long id) { return service.obtener(user.companiaId(), id); }

    @Operation(summary = "Factura uno o varios conduces abiertos del mismo cliente (emite el e-CF)")
    @PostMapping("/facturar")
    @PreAuthorize("@permisos.tiene('VENTAS_CONDUCES')")
    public ResponseEntity<FacturaDtos.FacturaResponse> facturar(AuthUser user, @Valid @RequestBody ConduceService.FacturarRequest r) {
        return ResponseEntity.status(201).body(service.facturar(user, r));
    }

    @PostMapping("/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('VENTAS_CONDUCES')")
    public ConduceService.Response anular(AuthUser user, @PathVariable Long id, @Valid @RequestBody ConduceService.AnularRequest r) {
        return service.anular(user, id, r.motivo());
    }
}
