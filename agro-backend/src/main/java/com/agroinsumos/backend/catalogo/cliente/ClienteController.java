package com.agroinsumos.backend.catalogo.cliente;

import com.agroinsumos.backend.common.crud.CrudController;
import com.agroinsumos.backend.common.crud.CrudService;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @Operation(summary = "Crédito del cliente: límite, lo que debe y lo disponible para facturar a crédito")
    @PreAuthorize("@permisos.tiene('VENTAS_POS','VENTAS_FACTURAS','CXC_RECIBOS','CAT_CLIENTES')")
    @GetMapping("/{id}/credito")
    public ClienteService.Credito credito(AuthUser user, @PathVariable Long id) {
        return service.credito(user.companiaId(), id);
    }
}
