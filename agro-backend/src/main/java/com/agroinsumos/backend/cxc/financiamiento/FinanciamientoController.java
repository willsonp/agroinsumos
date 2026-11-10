package com.agroinsumos.backend.cxc.financiamiento;

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
@RequestMapping("/api/v1/cxc/financiamientos")
@RequiredArgsConstructor
public class FinanciamientoController {

    private final FinanciamientoService service;

    @Operation(summary = "Financia el saldo de una factura a crédito en cuotas (el interés se emite como e-CF 33 exento)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_FINANCIAMIENTO')")
    public ResponseEntity<FinanciamientoService.Response> crear(AuthUser user, @Valid @RequestBody FinanciamientoService.Request r) {
        return ResponseEntity.status(201).body(service.crear(user, r));
    }

    @GetMapping
    @PreAuthorize("@permisos.tiene('CXC_FINANCIAMIENTO')")
    public Page<FinanciamientoService.Response> buscar(AuthUser user, @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) PlanFinanciamiento.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.buscar(user.companiaId(), clienteId, estado, p);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permisos.tiene('CXC_FINANCIAMIENTO','CXC_RECIBOS')")
    public FinanciamientoService.Response obtener(AuthUser user, @PathVariable Long id) { return service.obtener(user.companiaId(), id); }

    @Operation(summary = "Cuotas vencidas de los planes activos (por cliente o zona)")
    @GetMapping("/vencidas")
    @PreAuthorize("@permisos.tiene('CXC_FINANCIAMIENTO')")
    public List<FinanciamientoService.CuotaVencida> vencidas(AuthUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) Long clienteId, @RequestParam(required = false) Long zonaId) {
        return service.vencidas(user.companiaId(), fecha, clienteId, zonaId);
    }

    @Operation(summary = "Emite la nota de débito por la mora de las cuotas vencidas")
    @PostMapping("/{id}/mora")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_FINANCIAMIENTO')")
    public FinanciamientoService.Response mora(AuthUser user, @PathVariable Long id) { return service.cobrarMora(user, id); }

    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('CXC_FINANCIAMIENTO')")
    public FinanciamientoService.Response cancelar(AuthUser user, @PathVariable Long id,
                                                   @Valid @RequestBody FinanciamientoService.CancelarRequest r) {
        return service.cancelar(user, id, r.motivo());
    }
}
