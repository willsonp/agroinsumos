package com.agroinsumos.backend.venta.apartado;

import com.agroinsumos.backend.common.pago.PagoRequest;
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
@RequestMapping("/api/v1/ventas/apartados")
@RequiredArgsConstructor
public class ApartadoController {

    private final ApartadoService service;

    @Operation(summary = "Nuevo apartado (opcionalmente con el primer abono)")
    @PostMapping
    @PreAuthorize("@permisos.tiene('VENTAS_APARTADOS')")
    public ResponseEntity<ApartadoService.Response> crear(AuthUser user, @Valid @RequestBody ApartadoService.Request r) {
        return ResponseEntity.status(201).body(service.crear(user, r));
    }

    @GetMapping
    @PreAuthorize("@permisos.tiene('VENTAS_APARTADOS')")
    public Page<ApartadoService.Response> buscar(AuthUser user, @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Apartado.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), clienteId, estado, p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permisos.tiene('VENTAS_APARTADOS')")
    public ApartadoService.Response obtener(AuthUser user, @PathVariable Long id) { return service.obtener(user.companiaId(), id); }

    @Operation(summary = "Abono (el efectivo, tarjeta o cheque entra por la caja abierta del usuario)")
    @PostMapping("/{id}/abonos")
    @PreAuthorize("@permisos.tiene('VENTAS_APARTADOS')")
    public ApartadoService.Response abonar(AuthUser user, @PathVariable Long id, @Valid @RequestBody PagoRequest r) {
        return service.abonar(user, id, r);
    }

    @Operation(summary = "Factura el apartado saldado y lo cobra con los abonos")
    @PostMapping("/{id}/facturar")
    @PreAuthorize("@permisos.tiene('VENTAS_APARTADOS')")
    public ResponseEntity<FacturaDtos.FacturaResponse> facturar(AuthUser user, @PathVariable Long id,
                                                                @RequestBody(required = false) ApartadoService.FacturarRequest r) {
        return ResponseEntity.status(201).body(service.facturar(user, id, r));
    }

    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('VENTAS_APARTADOS')")
    public ApartadoService.Response cancelar(AuthUser user, @PathVariable Long id, @Valid @RequestBody ApartadoService.CancelarRequest r) {
        return service.cancelar(user, id, r);
    }
}
