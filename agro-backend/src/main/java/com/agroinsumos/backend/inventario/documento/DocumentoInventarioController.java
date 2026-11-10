package com.agroinsumos.backend.inventario.documento;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Inventario")
@RestController
@RequestMapping("/api/v1/inventario/documentos")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR') and @permisos.tiene('INV_ARTICULOS')")
@RequiredArgsConstructor
public class DocumentoInventarioController {

    private final DocumentoInventarioService service;

    @GetMapping
    public Page<DocumentoInventarioService.Response> listar(AuthUser user,
            @RequestParam(required = false) DocumentoInventario.Tipo tipo,
            @RequestParam(required = false) DocumentoInventario.Estado estado,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable p) {
        return service.listar(user.companiaId(), tipo, estado, p);
    }

    @GetMapping("/{id}")
    public DocumentoInventarioService.Response obtener(AuthUser user, @PathVariable Long id) {
        return service.obtener(user.companiaId(), id);
    }

    @Operation(summary = "Crea un ajuste/entrada/salida/transferencia en BORRADOR (no mueve inventario)")
    @PostMapping
    public ResponseEntity<DocumentoInventarioService.Response> crear(AuthUser user,
            @Valid @RequestBody DocumentoInventarioService.Request r) {
        return ResponseEntity.status(201).body(service.crear(user.companiaId(), r));
    }

    @PutMapping("/{id}")
    public DocumentoInventarioService.Response actualizar(AuthUser user, @PathVariable Long id,
            @Valid @RequestBody DocumentoInventarioService.Request r) {
        return service.actualizar(user.companiaId(), id, r);
    }

    @Operation(summary = "Aplica el documento: genera los movimientos de kardex y actualiza existencias")
    @PostMapping("/{id}/aplicar")
    public DocumentoInventarioService.Response aplicar(AuthUser user, @PathVariable Long id) {
        return service.aplicar(user.companiaId(), id);
    }

    @PostMapping("/{id}/anular")
    public ResponseEntity<Void> anular(AuthUser user, @PathVariable Long id) {
        service.anular(user.companiaId(), id);
        return ResponseEntity.noContent().build();
    }
}
