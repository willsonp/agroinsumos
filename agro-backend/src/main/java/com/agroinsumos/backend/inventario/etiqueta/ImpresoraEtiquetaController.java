package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inventario")
@RestController
@RequestMapping("/api/v1/inventario/impresoras")
@RequiredArgsConstructor
public class ImpresoraEtiquetaController extends CrudController<ImpresoraEtiquetaService.Request, ImpresoraEtiquetaService.Response> {
    private final ImpresoraEtiquetaService service;

    @Override protected CrudService<?, ImpresoraEtiquetaService.Request, ImpresoraEtiquetaService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "INV_ETIQUETAS"; }
}
