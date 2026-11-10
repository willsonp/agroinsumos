package com.agroinsumos.backend.catalogo.proveedor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProveedorRepository extends JpaRepository<Proveedor, Long> {
    @Query("""
            select e from Proveedor e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.rncCedula,''))) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Proveedor> buscar(Long companiaId, String q, Pageable pageable);

    boolean existsByCompaniaIdAndRncCedula(Long companiaId, String rncCedula);
}
