package com.agroinsumos.backend.contabilidad;

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
import java.util.Map;

@Tag(name = "Contabilidad")
@RestController
@RequestMapping("/api/v1/contabilidad")
@RequiredArgsConstructor
public class ContabilidadController {

    private static final String SUPERVISOR = "hasAnyRole('ADMIN','SUPERVISOR') and ";

    private final CuentaContableService cuentas;
    private final ConfigContableService config;
    private final AsientoService asientos;
    private final GeneracionAsientosService generacion;
    private final ReportesContablesService reportes;
    private final PeriodoContableService periodos;

    // ------------------------------------------------------------------ catálogo
    @Operation(summary = "Catálogo completo ordenado por código")
    @GetMapping("/cuentas/arbol")
    @PreAuthorize("@permisos.tiene('CONT_CATALOGO','CONT_ASIENTOS','CONT_REPORTES')")
    public List<CuentaContableService.Response> arbol(AuthUser user) { return cuentas.arbol(user.companiaId()); }

    @Operation(summary = "Carga el catálogo base y las cuentas por defecto (solo si la compañía no tiene catálogo)")
    @PostMapping("/cuentas/plantilla")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_CATALOGO')")
    public Map<String, Integer> plantilla(AuthUser user) { return Map.of("cuentas", cuentas.cargarPlantilla(user.companiaId())); }

    @GetMapping("/configuracion")
    @PreAuthorize("@permisos.tiene('CONT_CATALOGO','CONT_ASIENTOS')")
    public List<ConfigContableService.Item> configuracion(AuthUser user) { return config.listar(user.companiaId()); }

    @Operation(summary = "Cuentas por defecto de los asientos automáticos (código vacío = quitar)")
    @PutMapping("/configuracion")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_CATALOGO')")
    public List<ConfigContableService.Item> guardarConfiguracion(AuthUser user, @Valid @RequestBody List<ConfigContableService.Cambio> cambios) {
        return config.guardar(user.companiaId(), cambios);
    }

    @GetMapping("/configuracion/inicio")
    @PreAuthorize("@permisos.tiene('CONT_CATALOGO','CONT_ASIENTOS')")
    public ConfigContableService.Inicio inicio(AuthUser user) { return config.inicio(user.companiaId()); }

    @Operation(summary = "Fecha de inicio de la contabilidad: los documentos anteriores no generan asientos")
    @PutMapping("/configuracion/inicio")
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('CONT_CATALOGO')")
    public ConfigContableService.Inicio fijarInicio(AuthUser user, @RequestBody ConfigContableService.Inicio inicio) {
        return config.fijarInicio(user.companiaId(), inicio);
    }

    // ------------------------------------------------------------------ asientos
    @GetMapping("/asientos")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_ASIENTOS','CONT_REPORTES')")
    public Page<AsientoService.Response> buscar(AuthUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Asiento.Tipo tipo, @RequestParam(required = false) String origenTipo,
            @RequestParam(required = false) Long cuentaId,
            @PageableDefault(size = 50, sort = {"fecha", "numero"}, direction = Sort.Direction.DESC) Pageable p) {
        return asientos.buscar(user.companiaId(), desde, hasta, tipo, origenTipo, cuentaId, p);
    }

    @GetMapping("/asientos/{id}")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_ASIENTOS','CONT_REPORTES')")
    public AsientoService.Response obtener(AuthUser user, @PathVariable Long id) { return asientos.obtener(user.companiaId(), id); }

    @Operation(summary = "Asiento manual (entrada de diario)")
    @PostMapping("/asientos")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_ASIENTOS')")
    public ResponseEntity<AsientoService.Response> crear(AuthUser user, @Valid @RequestBody AsientoService.Request r) {
        return ResponseEntity.status(201).body(asientos.crearManual(user, r));
    }

    @PostMapping("/asientos/{id}/anular")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_ASIENTOS')")
    public AsientoService.Response anular(AuthUser user, @PathVariable Long id, @Valid @RequestBody AsientoService.AnularRequest r) {
        return asientos.anular(user, id, r.motivo());
    }

    @Operation(summary = "Genera los asientos automáticos del rango (simular=true solo muestra lo que se generaría)")
    @PostMapping("/asientos/generar")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_ASIENTOS')")
    public GeneracionAsientosService.Resultado generar(AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "false") boolean simular) {
        return generacion.generar(user, desde, hasta, simular);
    }

    // ------------------------------------------------------------------ reportes
    @GetMapping("/reportes/balanza")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_REPORTES')")
    public ReportesContablesService.Balanza balanza(AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "false") boolean todas) {
        return reportes.balanza(user.companiaId(), desde, hasta, todas);
    }

    @GetMapping("/reportes/estado-resultados")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_REPORTES')")
    public ReportesContablesService.EstadoResultados estadoResultados(AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.estadoResultados(user.companiaId(), desde, hasta);
    }

    @GetMapping("/reportes/balance-general")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_REPORTES')")
    public ReportesContablesService.BalanceGeneral balanceGeneral(AuthUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate corte) {
        return reportes.balanceGeneral(user.companiaId(), corte);
    }

    @GetMapping("/reportes/mayor/{cuentaId}")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_REPORTES')")
    public ReportesContablesService.Mayor mayor(AuthUser user, @PathVariable Long cuentaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return reportes.mayor(user.companiaId(), cuentaId, desde, hasta);
    }

    // ------------------------------------------------------------------ períodos
    @GetMapping("/periodos")
    @PreAuthorize(SUPERVISOR + "@permisos.tiene('CONT_PERIODOS','CONT_ASIENTOS')")
    public List<PeriodoContableService.Periodo> periodos(AuthUser user) { return periodos.cerrados(user.companiaId()); }

    @Operation(summary = "Cierra el mes: no se aceptan más asientos con fecha en él")
    @PostMapping("/periodos/{anio}/{mes}/cerrar")
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('CONT_PERIODOS')")
    public ResponseEntity<Void> cerrar(AuthUser user, @PathVariable int anio, @PathVariable int mes) {
        periodos.cerrar(user, anio, mes);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/periodos/{anio}/{mes}/reabrir")
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('CONT_PERIODOS')")
    public ResponseEntity<Void> reabrir(AuthUser user, @PathVariable int anio, @PathVariable int mes) {
        periodos.reabrir(user, anio, mes);
        return ResponseEntity.noContent().build();
    }
}
