package com.agroinsumos.backend.security.oauth2;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdentidadExternaRepository extends JpaRepository<IdentidadExterna, Long> {
    @EntityGraph(attributePaths = {"usuario", "usuario.compania"})
    Optional<IdentidadExterna> findByProveedorAndSujeto(String proveedor, String sujeto);
}
