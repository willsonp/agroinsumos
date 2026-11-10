package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@Tag(name = "Formatos fiscales DGII")
@RestController
@RequestMapping("/api/v1/fiscal")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('FISCAL_FORMATOS')")
@RequiredArgsConstructor
public class FormatosFiscalesController {

    private final FormatosFiscalesService service;
    private final ComprobanteAnuladoService anulados;

    @Operation(summary = "606 (compras) del periodo AAAAMM: detalle, totales y advertencias")
    @GetMapping("/606")
    public Formato606.Reporte f606(AuthUser user, @RequestParam String periodo) {
        return service.formato606(user.companiaId(), periodo);
    }

    @Operation(summary = "606 en TXT (layout oficial) para la herramienta de pre-validación / Oficina Virtual")
    @GetMapping(value = "/606/txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> f606Txt(AuthUser user, @RequestParam String periodo) {
        var rep = service.formato606(user.companiaId(), periodo);
        return archivo(service.txt606(rep), FormatosFiscalesService.nombreArchivo("606", rep.rnc(), rep.periodo()));
    }

    @Operation(summary = "607 (ventas) del periodo AAAAMM: detalle, totales y advertencias")
    @GetMapping("/607")
    public Formato607.Reporte f607(AuthUser user, @RequestParam String periodo,
                                   @RequestParam(defaultValue = "true") boolean incluirEcf) {
        return service.formato607(user.companiaId(), periodo, incluirEcf);
    }

    @GetMapping(value = "/607/txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> f607Txt(AuthUser user, @RequestParam String periodo,
                                          @RequestParam(defaultValue = "true") boolean incluirEcf) {
        var rep = service.formato607(user.companiaId(), periodo, incluirEcf);
        return archivo(service.txt607(rep), FormatosFiscalesService.nombreArchivo("607", rep.rnc(), rep.periodo()));
    }

    @Operation(summary = "608 (comprobantes anulados) del periodo AAAAMM")
    @GetMapping("/608")
    public Formato608.Reporte f608(AuthUser user, @RequestParam String periodo) {
        return service.formato608(user.companiaId(), periodo);
    }

    @GetMapping(value = "/608/txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> f608Txt(AuthUser user, @RequestParam String periodo) {
        var rep = service.formato608(user.companiaId(), periodo);
        return archivo(service.txt608(rep), FormatosFiscalesService.nombreArchivo("608", rep.rnc(), rep.periodo()));
    }

    @Operation(summary = "Comprobantes (NCF) registrados como anulados en el rango de fechas de anulación")
    @GetMapping("/anulaciones")
    public java.util.List<ComprobanteAnuladoService.Response> anulaciones(AuthUser user,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate desde,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate hasta) {
        return anulados.listar(user.companiaId(), desde, hasta);
    }

    @Operation(summary = "Registra un NCF anulado (preimpreso, serie B o e-NCF no utilizado) para el 608")
    @PostMapping("/anulaciones")
    public ResponseEntity<ComprobanteAnuladoService.Response> anular(AuthUser user,
            @jakarta.validation.Valid @RequestBody ComprobanteAnuladoService.Request r) {
        return ResponseEntity.status(201).body(anulados.registrar(user, r));
    }

    @DeleteMapping("/anulaciones/{id}")
    public ResponseEntity<Void> eliminarAnulacion(AuthUser user, @PathVariable Long id) {
        anulados.eliminar(user.companiaId(), id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<byte[]> archivo(String contenido, String nombre) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(contenido.getBytes(StandardCharsets.UTF_8));
    }
}
