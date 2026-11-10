package com.agroinsumos.backend.contabilidad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CuentaContableRepository extends JpaRepository<CuentaContable, Long> {
    @Query("""
            select c from CuentaContable c where c.companiaId = :cia and (:q is null
              or c.codigo like concat(cast(:q as string), '%') or lower(c.nombre) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<CuentaContable> buscar(Long cia, String q, Pageable pageable);

    Optional<CuentaContable> findByCompaniaIdAndCodigo(Long companiaId, String codigo);

    List<CuentaContable> findByCompaniaIdOrderByCodigo(Long companiaId);

    boolean existsByPadreIdAndEstado(Long padreId, String estado);

    long countByCompaniaId(Long companiaId);
}
