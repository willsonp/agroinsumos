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

    /** Selección para etiquetas y reportes: activos, por texto, departamento y (opcional) con existencia. */
    @Query("""
            select e from Articulo e where e.companiaId = :companiaId and e.estado = 'A'
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.codigoBarra,''), ' ', coalesce(e.referencia,'')))
                   like lower(concat('%', cast(:q as string), '%')))
              and (:departamentoId is null or e.departamentoId = :departamentoId)
              and (:conExistencia = false or exists (select 1 from Existencia x where x.articuloId = e.id and x.cantidad > 0))
            """)
    Page<Articulo> seleccionar(Long companiaId, String q, Long departamentoId, boolean conExistencia, Pageable pageable);

    Optional<Articulo> findByCompaniaIdAndCodigoBarra(Long companiaId, String codigoBarra);

    boolean existsByCompaniaIdAndCodigoBarra(Long companiaId, String codigoBarra);

    boolean existsByCompaniaIdAndCodigoBarraEmpaque(Long companiaId, String codigoBarraEmpaque);

    Optional<Articulo> findByCompaniaIdAndCodigoBarraEmpaque(Long companiaId, String codigoBarraEmpaque);
}
