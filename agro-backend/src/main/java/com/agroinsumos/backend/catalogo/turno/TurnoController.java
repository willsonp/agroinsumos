package com.agroinsumos.backend.catalogo.turno;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/turnos")
@RequiredArgsConstructor
public class TurnoController extends CrudController<TurnoService.Request, TurnoService.Response> {
    private final TurnoService service;

    @Override protected CrudService<?, TurnoService.Request, TurnoService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
