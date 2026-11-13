package com.agroinsumos.backend.caja;

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

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Caja")
@RestController
@RequestMapping("/api/v1/caja")
@RequiredArgsConstructor
public class CajaController {

    private final CajaService caja;
    private final VentaContadoService ventas;
    private final CierreCajaService cierres;

    @Operation(summary = "Abre la caja del usuario autenticado (una abierta por usuario)")
    @PreAuthorize("@permisos.tiene('CAJA_SESION')")
    @PostMapping("/sesiones/abrir")
    public ResponseEntity<CajaService.SesionResponse> abrir(AuthUser user, @Valid @RequestBody CajaService.AbrirRequest r) {
        return ResponseEntity.status(201).body(caja.abrir(user, r));
    }

    @Operation(summary = "Caja abierta del usuario con su cuadre en tiempo real (204 si no tiene caja abierta)")
    @PreAuthorize("@permisos.tiene('CAJA_SESION','VENTAS_POS')")
    @GetMapping("/sesiones/actual")
    public ResponseEntity<CajaService.SesionResponse> actual(AuthUser user) {
        return caja.actualOpcional(user).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("@permisos.tiene('CAJA_SESION')")
    @PostMapping("/sesiones/actual/egresos")
    public ResponseEntity<CajaService.MovimientoResponse> egreso(AuthUser user, @Valid @RequestBody CajaService.EgresoRequest r) {
        return ResponseEntity.status(201).body(caja.egreso(user, r));
    }

    @PreAuthorize("@permisos.tiene('CAJA_SESION')")
    @PostMapping("/sesiones/actual/ingresos")
    public ResponseEntity<CajaService.MovimientoResponse> ingreso(AuthUser user, @Valid @RequestBody CajaService.IngresoRequest r) {
        return ResponseEntity.status(201).body(caja.ingreso(user, r));
    }

    @Operation(summary = "Cierra la caja con el conteo de billetes/monedas y calcula la diferencia")
    @PreAuthorize("@permisos.tiene('CAJA_SESION')")
    @PostMapping("/sesiones/actual/cerrar")
    public CajaService.SesionResponse cerrar(AuthUser user, @Valid @RequestBody CajaService.CerrarRequest r) {
        return caja.cerrar(user, r);
    }

    @GetMapping("/sesiones")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CAJA_SESIONES')")
    public Page<CajaService.SesionResponse> sesiones(AuthUser user, @RequestParam(required = false) Long usuarioId,
            @RequestParam(required = false) CajaSesion.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return caja.listar(user.companiaId(), usuarioId, estado, p);
    }

    @Operation(summary = "Detalle de una caja: movimientos, conteo y cuadre (arqueo)")
    @PreAuthorize("@permisos.tiene('CAJA_SESION','CAJA_SESIONES')")
    @GetMapping("/sesiones/{id}")
    public CajaService.SesionDetalle detalle(AuthUser user, @PathVariable Long id) {
        return caja.detalle(user, id);
    }

    @Operation(summary = "Reporte de cierre: conceptos con cantidad de transacciones, subtotal, cuadre y desglose de monedas")
    @PreAuthorize("@permisos.tiene('CAJA_SESION','CAJA_SESIONES')")
    @GetMapping("/sesiones/{id}/cierre")
    public CierreCajaService.CierreResumen cierre(AuthUser user, @PathVariable Long id) {
        return cierres.resumen(user, id);
    }

    @PreAuthorize("@permisos.tiene('CAJA_SESION')")
    @GetMapping("/denominaciones")
    public List<BigDecimal> denominaciones() {
        return CajaService.DENOMINACIONES;
    }

    @Operation(summary = "Venta de contado en punto de venta: factura + e-CF + cobro en una sola operación")
    @PreAuthorize("@permisos.tiene('VENTAS_POS')")
    @PostMapping("/ventas")
    public ResponseEntity<VentaContadoService.Response> vender(AuthUser user, @Valid @RequestBody VentaContadoService.Request r) {
        return ResponseEntity.status(201).body(ventas.vender(user, r));
    }
}
