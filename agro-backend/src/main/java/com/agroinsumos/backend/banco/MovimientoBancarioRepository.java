package com.agroinsumos.backend.banco;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MovimientoBancarioRepository extends JpaRepository<MovimientoBancario, Long> {

    long countByCuentaId(Long cuentaId);

    Optional<MovimientoBancario> findByIdAndCompaniaId(Long id, Long companiaId);

    List<MovimientoBancario> findByOrigenTipoAndOrigenIdAndEstado(String origenTipo, Long origenId, MovimientoBancario.Estado estado);

    /** Entradas - salidas de los movimientos vigentes (reemplaza el proc. Balance_CuentaBanco). */
    @Query(value = """
            select coalesce(sum(case when tipo in ('DEPOSITO','TRANSFERENCIA_ENTRADA','NOTA_CREDITO') then monto else -monto end), 0)
              from movimiento_bancario
             where cuenta_id = :cuentaId and estado = 'REGISTRADO' and (cast(:hasta as date) is null or fecha <= :hasta)
            """, nativeQuery = true)
    BigDecimal netoHasta(Long cuentaId, LocalDate hasta);

    @Query("""
            select m from MovimientoBancario m where m.companiaId = :companiaId
              and (:cuentaId is null or m.cuentaId = :cuentaId)
              and (:tipo is null or m.tipo = :tipo)
              and (cast(:desde as date) is null or m.fecha >= :desde)
              and (cast(:hasta as date) is null or m.fecha <= :hasta)
            """)
    Page<MovimientoBancario> buscar(Long companiaId, Long cuentaId, TipoMovimientoBancario tipo, LocalDate desde,
                                    LocalDate hasta, Pageable pageable);

    /** Movimientos vigentes aún sin conciliar hasta la fecha de corte. */
    @Query("""
            select m from MovimientoBancario m where m.cuentaId = :cuentaId and m.estado = 'REGISTRADO'
              and m.conciliacionId is null and m.fecha <= :corte order by m.fecha, m.id
            """)
    List<MovimientoBancario> pendientesDeConciliar(Long cuentaId, LocalDate corte);
}
