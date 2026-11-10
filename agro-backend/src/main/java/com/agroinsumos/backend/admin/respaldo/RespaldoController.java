package com.agroinsumos.backend.admin.respaldo;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Administración")
@RestController
@RequestMapping("/api/v1/admin/respaldos")
@PreAuthorize("hasRole('ADMIN') and @permisos.tiene('ADMIN_RESPALDOS')")
@RequiredArgsConstructor
@Validated
public class RespaldoController {

    public record DirectorioRequest(@NotBlank @Size(max = 400) String directorio) {}

    private final RespaldoService service;

    @GetMapping
    public RespaldoService.Estado estado() { return service.estado(); }

    @Operation(summary = "Genera el respaldo de hoy (BK_<base>_<día>.sql) en la carpeta configurada")
    @PostMapping
    public RespaldoService.Resultado crear() { return service.crear(); }

    @Operation(summary = "Descarga un respaldo para guardarlo en otra ubicación")
    @GetMapping("/{nombre}")
    public ResponseEntity<Resource> descargar(@PathVariable String nombre) {
        var p = service.archivo(nombre);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + p.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(p));
    }

    @Operation(summary = "Carpeta del servidor donde se guardan los respaldos")
    @PutMapping("/directorio")
    public RespaldoService.Estado directorio(AuthUser user, @RequestBody @jakarta.validation.Valid DirectorioRequest r) {
        return service.configurarDirectorio(r.directorio(), user.username());
    }
}
