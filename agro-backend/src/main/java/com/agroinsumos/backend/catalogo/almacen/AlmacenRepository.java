package com.agroinsumos.backend.catalogo.almacen;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AlmacenRepository extends JpaRepository<Almacen, Long> {
    @Query("""
            select e from Almacen e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.ubicacion,''))) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Almacen> buscar(Long companiaId, String q, Pageable pageable);

    Optional<Almacen> findByCompaniaIdAndPrincipalTrue(Long companiaId);
}
