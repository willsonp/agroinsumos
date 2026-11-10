package com.agroinsumos.backend.catalogo.zona;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/zonas")
@RequiredArgsConstructor
public class ZonaController extends CrudController<ZonaService.Request, ZonaService.Response> {
    private final ZonaService service;

    @Override protected CrudService<?, ZonaService.Request, ZonaService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
