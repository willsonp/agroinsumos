package com.agroinsumos.backend.banco;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConciliacionBancariaRepository extends JpaRepository<ConciliacionBancaria, Long> {
    @Query("select max(c.fechaCorte) from ConciliacionBancaria c where c.cuentaId = :cuentaId")
    Optional<LocalDate> ultimaFechaCorte(Long cuentaId);

    List<ConciliacionBancaria> findByCuentaIdOrderByFechaCorteDesc(Long cuentaId);
}
