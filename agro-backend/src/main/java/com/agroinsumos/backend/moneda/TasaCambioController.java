package com.agroinsumos.backend.moneda;

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

@Tag(name = "Monedas")
@RestController
@RequestMapping("/api/v1/monedas/tasas")
@RequiredArgsConstructor
public class TasaCambioController {

    private final TasaCambioService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<TasaCambioService.Tasa> listar(AuthUser user, @RequestParam(required = false) String moneda) {
        return service.listar(user.companiaId(), moneda);
    }

    @Operation(summary = "Tasa vigente de la moneda a la fecha (la última registrada en o antes de ella)")
    @GetMapping("/vigente")
    @PreAuthorize("isAuthenticated()")
    public TasaCambioService.Tasa vigente(AuthUser user, @RequestParam String moneda,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return service.vigente(user.companiaId(), moneda, fecha != null ? fecha : LocalDate.now());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_TASAS')")
    public ResponseEntity<TasaCambioService.Tasa> registrar(AuthUser user, @Valid @RequestBody TasaCambioService.Request r) {
        return ResponseEntity.status(201).body(service.registrar(user, r));
    }

    @DeleteMapping("/{moneda}/{fecha}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('BANCO_TASAS')")
    public ResponseEntity<Void> eliminar(AuthUser user, @PathVariable String moneda,
                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        service.eliminar(user.companiaId(), moneda, fecha);
        return ResponseEntity.noContent().build();
    }
}
