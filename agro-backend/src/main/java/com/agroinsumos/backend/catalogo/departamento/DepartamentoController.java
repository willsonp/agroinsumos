package com.agroinsumos.backend.catalogo.departamento;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/departamentos")
@RequiredArgsConstructor
public class DepartamentoController extends CrudController<DepartamentoService.Request, DepartamentoService.Response> {
    private final DepartamentoService service;

    @Override protected CrudService<?, DepartamentoService.Request, DepartamentoService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
