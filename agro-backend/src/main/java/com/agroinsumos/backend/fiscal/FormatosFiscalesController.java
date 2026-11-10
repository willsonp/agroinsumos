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

    private static ResponseEntity<byte[]> archivo(String contenido, String nombre) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(contenido.getBytes(StandardCharsets.UTF_8));
    }
}
