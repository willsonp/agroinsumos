package com.agroinsumos.backend.venta.factura;

import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.devolucion.DevolucionService;
import com.agroinsumos.backend.venta.factura.FacturaDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "Ventas")
@RestController
@RequestMapping("/api/v1/ventas/facturas")
@PreAuthorize("@permisos.tiene('VENTAS_FACTURAS')")
@RequiredArgsConstructor
public class FacturaController {

    private final FacturaService service;
    private final DevolucionService devoluciones;

    @Operation(summary = "Factura: valida precios/crédito, descarga inventario y emite el e-CF firmado (una transacción)")
    @PreAuthorize("@permisos.tiene('VENTAS_POS')")
    @PostMapping
    public ResponseEntity<FacturaResponse> crear(AuthUser user, @Valid @RequestBody CrearFacturaRequest r) {
        var f = service.crear(user.companiaId(), user, r);
        return ResponseEntity.created(URI.create("/api/v1/ventas/facturas/" + f.id())).body(f);
    }

    @GetMapping
    public Page<FacturaResumen> buscar(AuthUser user, @RequestParam(required = false) Long clienteId,
                                       @RequestParam(required = false) Factura.Estado estado,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                       @RequestParam(required = false) Long numero,
                                       @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), clienteId, estado, desde, hasta, numero, p);
    }

    @GetMapping("/{id}")
    public FacturaResponse obtener(AuthUser user, @PathVariable Long id) {
        return service.obtener(user.companiaId(), id);
    }

    @Operation(summary = "Anula la factura emitiendo una Nota de Crédito electrónica (e-CF 34) por el total pendiente")
    @PostMapping("/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('VENTAS_FACTURAS')")
    public DevolucionService.Response anular(AuthUser user, @PathVariable Long id, @Valid @RequestBody AnularRequest r) {
        return devoluciones.anularFactura(user.companiaId(), user, id, r.motivo());
    }

    @GetMapping("/{id}/devoluciones")
    public List<DevolucionService.Response> devoluciones(AuthUser user, @PathVariable Long id) {
        return devoluciones.deFactura(user.companiaId(), id);
    }
}
