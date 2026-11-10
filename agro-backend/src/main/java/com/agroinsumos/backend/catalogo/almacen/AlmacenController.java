package com.agroinsumos.backend.catalogo.almacen;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/almacenes")
@RequiredArgsConstructor
public class AlmacenController extends CrudController<AlmacenService.Request, AlmacenService.Response> {
    private final AlmacenService service;

    @Override protected CrudService<?, AlmacenService.Request, AlmacenService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
