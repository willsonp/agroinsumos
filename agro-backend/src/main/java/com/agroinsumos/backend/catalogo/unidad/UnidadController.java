package com.agroinsumos.backend.catalogo.unidad;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/unidades")
@RequiredArgsConstructor
public class UnidadController extends CrudController<UnidadService.Request, UnidadService.Response> {
    private final UnidadService service;

    @Override protected CrudService<?, UnidadService.Request, UnidadService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
