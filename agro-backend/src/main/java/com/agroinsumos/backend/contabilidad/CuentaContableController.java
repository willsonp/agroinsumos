package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Contabilidad")
@RestController
@RequestMapping("/api/v1/contabilidad/cuentas")
@RequiredArgsConstructor
public class CuentaContableController extends CrudController<CuentaContableService.Request, CuentaContableService.Response> {
    private final CuentaContableService service;

    @Override protected CrudService<?, CuentaContableService.Request, CuentaContableService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CONT_CATALOGO"; }
}
