package com.agroinsumos.backend.cxc;

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

@Tag(name = "Cuentas por Cobrar")
@RestController
@RequestMapping("/api/v1/cxc")
@RequiredArgsConstructor
public class CxcController {

    private final ReciboService recibos;
    private final NotaDebitoService notasDebito;
    private final EstadoCuentaService estadoCuenta;

    @Operation(summary = "Recibo de ingreso: abona facturas/notas de débito. Pagos en efectivo/tarjeta/cheque requieren caja abierta")
    @PostMapping("/recibos")
    @PreAuthorize("@permisos.tiene('CXC_RECIBOS')")
    public ResponseEntity<ReciboService.ReciboResponse> cobrar(AuthUser user, @Valid @RequestBody ReciboService.ReciboRequest r) {
        return ResponseEntity.status(201).body(recibos.cobrar(user, r));
    }

    @GetMapping("/recibos")
    @PreAuthorize("@permisos.tiene('CXC_RECIBOS')")
    public Page<ReciboService.ReciboResponse> recibos(AuthUser user, @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long numero,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return recibos.buscar(user.companiaId(), clienteId, desde, hasta, numero, p);
    }

    @GetMapping("/recibos/{id}")
    @PreAuthorize("@permisos.tiene('CXC_RECIBOS','CXC_ESTADO_CUENTA')")
    public ReciboService.ReciboResponse recibo(AuthUser user, @PathVariable Long id) {
        return recibos.obtener(user.companiaId(), id);
    }

    @PostMapping("/recibos/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_RECIBOS')")
    public ReciboService.ReciboResponse anular(AuthUser user, @PathVariable Long id,
                                               @Valid @RequestBody ReciboService.AnularRequest r) {
        return recibos.anular(user, id, r.motivo());
    }

    @Operation(summary = "Nota de débito al cliente (emite e-CF 33 referenciando la factura)")
    @PostMapping("/notas-debito")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_RECIBOS')")
    public ResponseEntity<NotaDebitoService.Response> notaDebito(AuthUser user, @Valid @RequestBody NotaDebitoService.Request r) {
        return ResponseEntity.status(201).body(notasDebito.emitir(user, r));
    }

    @GetMapping("/notas-debito/{id}")
    @PreAuthorize("@permisos.tiene('CXC_RECIBOS','CXC_ESTADO_CUENTA')")
    public NotaDebitoService.Response notaDebito(AuthUser user, @PathVariable Long id) {
        return notasDebito.obtener(user.companiaId(), id);
    }

    @Operation(summary = "Documentos con balance pendiente del cliente (para armar el recibo)")
    @GetMapping("/clientes/{clienteId}/pendientes")
    @PreAuthorize("@permisos.tiene('CXC_RECIBOS','CXC_ESTADO_CUENTA')")
    public List<EstadoCuentaService.DocumentoPendiente> pendientes(AuthUser user, @PathVariable Long clienteId) {
        return estadoCuenta.pendientes(user.companiaId(), clienteId);
    }

    @GetMapping("/clientes/{clienteId}/estado-cuenta")
    @PreAuthorize("@permisos.tiene('CXC_ESTADO_CUENTA')")
    public EstadoCuentaService.EstadoCuenta estadoCuenta(AuthUser user, @PathVariable Long clienteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return estadoCuenta.estadoCuenta(user.companiaId(), clienteId, desde, hasta);
    }

    @GetMapping("/antiguedad")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_ESTADO_CUENTA')")
    public List<EstadoCuentaService.Antiguedad> antiguedad(AuthUser user) {
        return estadoCuenta.antiguedad(user.companiaId());
    }
}
