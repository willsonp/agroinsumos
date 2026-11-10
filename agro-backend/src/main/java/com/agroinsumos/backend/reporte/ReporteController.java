package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Reportes gerenciales: supervisor o administrador con la opción del grupo (se valida por reporte). */
@Tag(name = "Reportes")
@RestController
@RequestMapping("/api/v1/reportes")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('REP_VENTAS','REP_CXC_CAJA','REP_INVENTARIO','REP_COMPRAS')")
@RequiredArgsConstructor
public class ReporteController {

    private final ReporteService service;

    @Operation(summary = "Reportes disponibles para el usuario, con sus filtros y columnas")
    @GetMapping
    public List<Reporte.Info> disponibles() { return service.disponibles(); }

    @GetMapping("/{codigo}")
    public Reporte.Resultado ejecutar(AuthUser user, @PathVariable String codigo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate corte,
            @RequestParam(required = false) Long clienteId, @RequestParam(required = false) Long vendedorId,
            @RequestParam(required = false) Long departamentoId, @RequestParam(required = false) Long almacenId,
            @RequestParam(required = false) Long proveedorId, @RequestParam(defaultValue = "false") boolean conExistencia) {
        return service.ejecutar(user.companiaId(), codigo, new Reporte.Parametros(desde, hasta, corte, clienteId, vendedorId,
                departamentoId, almacenId, proveedorId, conExistencia));
    }

    @Operation(summary = "El reporte en CSV para Excel")
    @GetMapping(value = "/{codigo}/csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(AuthUser user, @PathVariable String codigo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate corte,
            @RequestParam(required = false) Long clienteId, @RequestParam(required = false) Long vendedorId,
            @RequestParam(required = false) Long departamentoId, @RequestParam(required = false) Long almacenId,
            @RequestParam(required = false) Long proveedorId, @RequestParam(defaultValue = "false") boolean conExistencia) {
        byte[] datos = service.csv(user.companiaId(), codigo, new Reporte.Parametros(desde, hasta, corte, clienteId, vendedorId,
                departamentoId, almacenId, proveedorId, conExistencia));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + codigo + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(datos);
    }
}
