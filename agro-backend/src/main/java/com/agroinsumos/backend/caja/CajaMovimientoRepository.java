package com.agroinsumos.backend.caja;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CajaMovimientoRepository extends JpaRepository<CajaMovimiento, Long> {
    List<CajaMovimiento> findBySesionIdOrderById(Long sesionId);

    /** Totales netos por forma de pago y tipo, para el cuadre. */
    @Query("select m.formaPago, m.tipo, sum(m.monto) from CajaMovimiento m where m.sesionId = :sesionId group by m.formaPago, m.tipo")
    List<Object[]> totales(Long sesionId);

    @Query("""
            select coalesce(sum(m.monto), 0) from CajaMovimiento m
            where m.referenciaTipo = :refTipo and m.referenciaId = :refId and m.tipo = :tipo
            """)
    BigDecimal totalReferencia(String refTipo, Long refId, CajaMovimiento.Tipo tipo);
}
