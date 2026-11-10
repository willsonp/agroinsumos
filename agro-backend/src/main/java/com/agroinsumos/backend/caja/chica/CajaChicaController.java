package com.agroinsumos.backend.caja.chica;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Caja")
@RestController
@RequestMapping("/api/v1/caja/chica")
@RequiredArgsConstructor
public class CajaChicaController {

    private static final String SUPERVISOR = "hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CAJA_CHICA')";

    private final CajaChicaService service;

    @GetMapping
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public List<CajaChicaService.FondoResponse> listar(AuthUser user) { return service.listar(user.companiaId()); }

    @Operation(summary = "Crea el fondo y registra su apertura (cheque, transferencia o efectivo de la caja)")
    @PostMapping
    @PreAuthorize(SUPERVISOR)
    public ResponseEntity<CajaChicaService.FondoResponse> crear(AuthUser user, @Valid @RequestBody CajaChicaService.FondoRequest r) {
        return ResponseEntity.status(201).body(service.crear(user, r));
    }

    @PutMapping("/{id}")
    @PreAuthorize(SUPERVISOR)
    public CajaChicaService.FondoResponse actualizar(AuthUser user, @PathVariable Long id, @Valid @RequestBody CajaChicaService.FondoRequest r) {
        return service.actualizar(user, id, r);
    }

    @GetMapping("/{id}/movimientos")
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public List<CajaChicaService.MovimientoResponse> movimientos(AuthUser user, @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.movimientos(user.companiaId(), id, desde, hasta);
    }

    @Operation(summary = "Gasto (vale) pagado con la caja chica; con NCF va al 606")
    @PostMapping("/{id}/gastos")
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public ResponseEntity<CajaChicaService.MovimientoResponse> gasto(AuthUser user, @PathVariable Long id,
                                                                     @Valid @RequestBody CajaChicaService.GastoRequest r) {
        return ResponseEntity.status(201).body(service.gasto(user, id, r));
    }

    @PostMapping("/movimientos/{movId}/anular")
    @PreAuthorize(SUPERVISOR)
    public CajaChicaService.MovimientoResponse anular(AuthUser user, @PathVariable Long movId, @Valid @RequestBody CajaChicaService.AnularRequest r) {
        return service.anularGasto(user, movId, r.motivo());
    }

    @Operation(summary = "Repone lo gastado y deja el fondo completo")
    @PostMapping("/{id}/reposiciones")
    @PreAuthorize(SUPERVISOR)
    public ResponseEntity<CajaChicaService.Reposicion> reponer(AuthUser user, @PathVariable Long id, @Valid @RequestBody CajaChicaService.Origen r) {
        return ResponseEntity.status(201).body(service.reponer(user, id, r));
    }

    @Operation(summary = "Detalle de una reposición (gastos incluidos) para imprimir")
    @GetMapping("/reposiciones/{movId}")
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public CajaChicaService.Reposicion reposicion(AuthUser user, @PathVariable Long movId) { return service.reposicion(user.companiaId(), movId); }

    @PostMapping("/{id}/arqueos")
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public ResponseEntity<CajaChicaService.Arqueo> arqueo(AuthUser user, @PathVariable Long id, @Valid @RequestBody CajaChicaService.ArqueoRequest r) {
        return ResponseEntity.status(201).body(service.arqueo(user, id, r));
    }

    @GetMapping("/{id}/arqueos")
    @PreAuthorize("@permisos.tiene('CAJA_CHICA')")
    public List<CajaChicaService.Arqueo> arqueos(AuthUser user, @PathVariable Long id) { return service.arqueos(user.companiaId(), id); }
}
