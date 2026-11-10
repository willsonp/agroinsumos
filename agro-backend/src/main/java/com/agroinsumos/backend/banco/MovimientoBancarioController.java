package com.agroinsumos.backend.banco;

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

@Tag(name = "Banco")
@RestController
@RequestMapping("/api/v1/banco")
@RequiredArgsConstructor
public class MovimientoBancarioController {

    private final MovimientoBancarioService movimientos;
    private final ConciliacionService conciliacion;

    @GetMapping("/movimientos")
    @PreAuthorize("@permisos.tiene('BANCO_MOVIMIENTOS','BANCO_CONCILIACION')")
    public Page<MovimientoBancarioService.Response> buscar(AuthUser user, @RequestParam(required = false) Long cuentaId,
            @RequestParam(required = false) TipoMovimientoBancario tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @PageableDefault(size = 50, sort = {"fecha", "id"}, direction = Sort.Direction.DESC) Pageable p) {
        return movimientos.buscar(user.companiaId(), cuentaId, tipo, desde, hasta, p);
    }

    @Operation(summary = "Depósito (opcionalmente del efectivo de la caja), cheque, nota de débito o de crédito")
    @PostMapping("/movimientos")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_MOVIMIENTOS')")
    public ResponseEntity<MovimientoBancarioService.Response> registrar(AuthUser user,
            @Valid @RequestBody MovimientoBancarioService.RegistrarRequest r) {
        return ResponseEntity.status(201).body(movimientos.registrarManual(user, r));
    }

    @Operation(summary = "Transferencia entre cuentas propias (genera salida y entrada enlazadas)")
    @PostMapping("/transferencias")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_MOVIMIENTOS')")
    public ResponseEntity<MovimientoBancarioService.Response> transferir(AuthUser user,
            @Valid @RequestBody MovimientoBancarioService.TransferenciaRequest r) {
        return ResponseEntity.status(201).body(movimientos.transferir(user, r));
    }

    @PostMapping("/movimientos/{id}/anular")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_MOVIMIENTOS')")
    public MovimientoBancarioService.Response anular(AuthUser user, @PathVariable Long id,
                                                     @Valid @RequestBody MovimientoBancarioService.AnularRequest r) {
        return movimientos.anular(user, id, r.motivo());
    }

    @Operation(summary = "Indica la cuenta contable de contrapartida de un movimiento manual aún no contabilizado")
    @PutMapping("/movimientos/{id}/contrapartida")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_MOVIMIENTOS','CONT_ASIENTOS')")
    public MovimientoBancarioService.Response contrapartida(AuthUser user, @PathVariable Long id,
                                                            @Valid @RequestBody MovimientoBancarioService.ContrapartidaRequest r) {
        return movimientos.fijarContrapartida(user, id, r.cuentaContrapartida());
    }

    @Operation(summary = "Vista previa de la conciliación con los movimientos marcados (no guarda)")
    @PostMapping("/conciliaciones/calcular")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_CONCILIACION')")
    public ConciliacionService.Resultado calcular(AuthUser user, @Valid @RequestBody ConciliacionService.Request r) {
        return conciliacion.calcular(user.companiaId(), r);
    }

    @Operation(summary = "Cierra la conciliación (solo si cuadra con el saldo del banco)")
    @PostMapping("/conciliaciones")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_CONCILIACION')")
    public ResponseEntity<ConciliacionService.ConciliacionResponse> conciliar(AuthUser user, @Valid @RequestBody ConciliacionService.Request r) {
        return ResponseEntity.status(201).body(conciliacion.conciliar(user, r));
    }

    @GetMapping("/cuentas/{cuentaId}/conciliaciones")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_CONCILIACION')")
    public List<ConciliacionService.ConciliacionResponse> historial(AuthUser user, @PathVariable Long cuentaId) {
        return conciliacion.historial(user.companiaId(), cuentaId);
    }
}
