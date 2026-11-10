package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Inventario")
@RestController
@RequestMapping("/api/v1/inventario/etiquetas")
@PreAuthorize("@permisos.tiene('INV_ETIQUETAS')")
@RequiredArgsConstructor
public class EtiquetaController {

    private final EtiquetaService service;

    @GetMapping("/tamanos")
    public List<EtiquetaService.Tamano> tamanos() { return EtiquetaService.TAMANOS; }

    @Operation(summary = "Diseño (vista previa) y ZPL de las etiquetas")
    @PostMapping("/vista-previa")
    public EtiquetaService.Response vistaPrevia(AuthUser user, @Valid @RequestBody EtiquetaService.Request r) {
        return service.generar(user.companiaId(), r);
    }

    @Operation(summary = "Archivo .zpl para enviar a la impresora (Zebra Setup Utilities, copia RAW, etc.)")
    @PostMapping(value = "/zpl", produces = "application/octet-stream")
    public ResponseEntity<byte[]> zpl(AuthUser user, @Valid @RequestBody EtiquetaService.Request r) {
        var gen = service.generar(user.companiaId(), r);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"etiquetas.zpl\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(gen.zpl().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Envía las etiquetas a una impresora de red registrada (puerto RAW 9100)")
    @PostMapping("/imprimir/{impresoraId}")
    public EtiquetaService.Impresion imprimir(AuthUser user, @PathVariable Long impresoraId, @Valid @RequestBody EtiquetaService.Request r) {
        return service.imprimir(user.companiaId(), impresoraId, r);
    }
}
