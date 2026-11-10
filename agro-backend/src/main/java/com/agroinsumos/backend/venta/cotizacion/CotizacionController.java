package com.agroinsumos.backend.venta.cotizacion;

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
@RequestMapping("/api/v1/ventas/cotizaciones")
@PreAuthorize("@permisos.tiene('VENTAS_POS','VENTAS_FACTURAS')")
@RequiredArgsConstructor
public class CotizacionController {

    private final CotizacionService service;

    @PostMapping
    public ResponseEntity<CotizacionService.Response> crear(AuthUser user, @Valid @RequestBody CotizacionService.Request r) {
        return ResponseEntity.status(201).body(service.crear(user.companiaId(), user, r));
    }

    @GetMapping
    public Page<CotizacionService.Response> buscar(AuthUser user, @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Cotizacion.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), clienteId, estado, p);
    }

    @GetMapping("/{id}")
    public CotizacionService.Response obtener(AuthUser user, @PathVariable Long id) {
        return service.obtener(user.companiaId(), id);
    }

    @Operation(summary = "Convierte la cotización en factura (con e-CF) respetando los precios cotizados")
    @PostMapping("/{id}/facturar")
    public ResponseEntity<FacturaDtos.FacturaResponse> facturar(AuthUser user, @PathVariable Long id,
                                                                @Valid @RequestBody CotizacionService.FacturarRequest r) {
        return ResponseEntity.status(201).body(service.facturar(user.companiaId(), user, id, r));
    }

    @PostMapping("/{id}/anular")
    public ResponseEntity<Void> anular(AuthUser user, @PathVariable Long id) {
        service.anular(user.companiaId(), id);
        return ResponseEntity.noContent().build();
    }
}
