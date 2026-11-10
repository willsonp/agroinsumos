package com.agroinsumos.backend.compra;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PagoProveedorRepository extends JpaRepository<PagoProveedor, Long> {
    Optional<PagoProveedor> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("select p from PagoProveedor p where p.companiaId = :companiaId and (:proveedorId is null or p.proveedorId = :proveedorId)")
    Page<PagoProveedor> buscar(Long companiaId, Long proveedorId, Pageable pageable);
}
