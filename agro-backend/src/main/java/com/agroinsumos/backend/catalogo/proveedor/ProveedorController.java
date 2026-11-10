package com.agroinsumos.backend.catalogo.proveedor;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catálogos")
@RestController
@RequestMapping("/api/v1/catalogos/proveedores")
@RequiredArgsConstructor
public class ProveedorController extends CrudController<ProveedorService.Request, ProveedorService.Response> {
    private final ProveedorService service;

    @Override protected CrudService<?, ProveedorService.Request, ProveedorService.Response> service() { return service; }
    @Override protected String permisoEscritura() { return "CAT_PROVEEDORES"; }
}
