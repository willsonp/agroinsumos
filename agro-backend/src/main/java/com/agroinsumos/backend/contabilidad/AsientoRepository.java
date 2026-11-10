package com.agroinsumos.backend.contabilidad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface AsientoRepository extends JpaRepository<Asiento, Long> {
    Optional<Asiento> findByIdAndCompaniaId(Long id, Long companiaId);

    Optional<Asiento> findByCompaniaIdAndOrigenTipoAndOrigenIdAndReversoFalse(Long companiaId, String origenTipo, Long origenId);

    @Query("""
            select a from Asiento a where a.companiaId = :cia
              and (cast(:desde as date) is null or a.fecha >= :desde)
              and (cast(:hasta as date) is null or a.fecha <= :hasta)
              and (:tipo is null or a.tipo = :tipo)
              and (:origenTipo is null or a.origenTipo = :origenTipo)
              and (:cuentaId is null or exists (select 1 from a.lineas l where l.cuentaId = :cuentaId))
            """)
    Page<Asiento> buscar(Long cia, LocalDate desde, LocalDate hasta, Asiento.Tipo tipo, String origenTipo, Long cuentaId, Pageable p);
}
