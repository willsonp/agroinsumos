package com.agroinsumos.backend.catalogo.cliente;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/clientes")
@RequiredArgsConstructor
public class ClienteController extends CrudController<ClienteService.Request, ClienteService.Response> {
    private final ClienteService service;

    @Override protected CrudService<?, ClienteService.Request, ClienteService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_CLIENTES"; }
}
