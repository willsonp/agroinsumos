package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Banco")
@RestController
@RequestMapping("/api/v1/banco/cuentas")
@RequiredArgsConstructor
public class CuentaBancariaController extends CrudController<CuentaBancariaService.Request, CuentaBancariaService.Response> {
    private final CuentaBancariaService service;

    @Override protected CrudService<?, CuentaBancariaService.Request, CuentaBancariaService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "BANCO_CUENTAS"; }
}
