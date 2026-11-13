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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Compras y Cuentas por Pagar")
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO')")
@RequiredArgsConstructor
public class CompraController {

    private final CompraService compras;
    private final PagoProveedorService pagos;

    @Operation(summary = "Registra factura de suplidor: entra al inventario y actualiza costo promedio")
    @PostMapping("/compras")
    public ResponseEntity<CompraService.Response> registrar(AuthUser user, @Valid @RequestBody CompraService.Request r) {
        return ResponseEntity.status(201).body(compras.registrar(user, r));
    }

    @GetMapping("/compras")
    public Page<CompraService.Response> buscar(AuthUser user, @RequestParam(required = false) Long proveedorId,
            @RequestParam(required = false) Compra.Estado estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return compras.buscar(user.companiaId(), proveedorId, estado, desde, hasta, p);
    }

    @GetMapping("/compras/{id}")
    public CompraService.Response obtener(AuthUser user, @PathVariable Long id) {
        return compras.obtener(user.companiaId(), id);
    }

    @PostMapping("/compras/{id}/anular")
    public CompraService.Response anular(AuthUser user, @PathVariable Long id) {
        return compras.anular(user, id);
    }

    @Operation(summary = "Pago a suplidor (el efectivo sale de la caja abierta del usuario)")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @PostMapping("/cxp/pagos")
    public ResponseEntity<PagoProveedorService.Response> pagar(AuthUser user, @Valid @RequestBody PagoProveedorService.Request r) {
        return ResponseEntity.status(201).body(pagos.pagar(user, r));
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @GetMapping("/cxp/pagos")
    public Page<PagoProveedorService.Response> listarPagos(AuthUser user, @RequestParam(required = false) Long proveedorId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return pagos.buscar(user.companiaId(), proveedorId, p);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @GetMapping("/cxp/pagos/{id}")
    public PagoProveedorService.Response pago(AuthUser user, @PathVariable Long id) {
        return pagos.obtener(user.companiaId(), id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @PostMapping("/cxp/pagos/{id}/anular")
    public PagoProveedorService.Response anularPago(AuthUser user, @PathVariable Long id,
                                                    @Valid @RequestBody PagoProveedorService.AnularRequest r) {
        return pagos.anular(user, id, r.motivo());
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @GetMapping("/cxp/pagos/{id}/impresion")
    public PagoProveedorService.PagoImpresion impresionPago(AuthUser user, @PathVariable Long id) {
        return pagos.impresion(user.companiaId(), id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @GetMapping("/cxp/proveedores/{proveedorId}/pendientes")
    public List<PagoProveedorService.CompraPendiente> pendientes(AuthUser user, @PathVariable Long proveedorId) {
        return pagos.pendientes(user.companiaId(), proveedorId);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('COMPRAS_REGISTRO','CXP_PAGOS')")
    @GetMapping("/cxp/antiguedad")
    public List<PagoProveedorService.Antiguedad> antiguedad(AuthUser user) {
        return pagos.antiguedad(user.companiaId());
    }
}
