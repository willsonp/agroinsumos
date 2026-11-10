package com.agroinsumos.backend.compania;

import com.agroinsumos.backend.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Administración")
@RestController
@RequestMapping("/api/v1/admin/empresa")
@RequiredArgsConstructor
public class CompaniaController {

    private final CompaniaService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public CompaniaService.Response obtener(AuthUser user) { return service.obtener(user.companiaId()); }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN') and @permisos.tiene('ADMIN_EMPRESA')")
    public CompaniaService.Response actualizar(AuthUser user, @Valid @RequestBody CompaniaService.Request r) {
        return service.actualizar(user.companiaId(), r);
    }
}
