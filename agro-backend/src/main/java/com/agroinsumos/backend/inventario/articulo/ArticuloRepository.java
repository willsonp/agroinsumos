package com.agroinsumos.backend.inventario.articulo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ArticuloRepository extends JpaRepository<Articulo, Long> {
    @Query("""
            select e from Articulo e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.codigoBarra,''), ' ', coalesce(e.referencia,'')))
                   like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Articulo> buscar(Long companiaId, String q, Pageable pageable);

    Optional<Articulo> findByCompaniaIdAndCodigoBarra(Long companiaId, String codigoBarra);

    boolean existsByCompaniaIdAndCodigoBarra(Long companiaId, String codigoBarra);
}
