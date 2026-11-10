package com.agroinsumos.backend.catalogo.ciudad;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/ciudades")
@RequiredArgsConstructor
public class CiudadController extends CrudController<CiudadService.Request, CiudadService.Response> {
    private final CiudadService service;

    @Override protected CrudService<?, CiudadService.Request, CiudadService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
