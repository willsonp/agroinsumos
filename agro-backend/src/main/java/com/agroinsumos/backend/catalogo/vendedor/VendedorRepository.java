package com.agroinsumos.backend.catalogo.vendedor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface VendedorRepository extends JpaRepository<Vendedor, Long> {
    @Query("""
            select e from Vendedor e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.rncCedula,''))) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Vendedor> buscar(Long companiaId, String q, Pageable pageable);

    Optional<Vendedor> findByUsuarioId(Long usuarioId);
}
