package com.agroinsumos.backend.catalogo.vendedor;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/vendedores")
@RequiredArgsConstructor
public class VendedorController extends CrudController<VendedorService.Request, VendedorService.Response> {
    private final VendedorService service;

    @Override protected CrudService<?, VendedorService.Request, VendedorService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_GENERALES"; }
}
