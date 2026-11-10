package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Banco")
@RestController
@RequestMapping("/api/v1/banco/bancos")
@RequiredArgsConstructor
public class BancoController extends CrudController<BancoService.Request, BancoService.Response> {
    private final BancoService service;

    @Override protected CrudService<?, BancoService.Request, BancoService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "BANCO_CUENTAS"; }
}
