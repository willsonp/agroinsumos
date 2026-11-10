package com.agroinsumos.backend.caja.chica;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CajaChicaRepository extends JpaRepository<CajaChica, Long> {
    List<CajaChica> findByCompaniaIdOrderByNombre(Long companiaId);

    boolean existsByCompaniaIdAndNombreIgnoreCase(Long companiaId, String nombre);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CajaChica c where c.id = :id and c.companiaId = :cia")
    Optional<CajaChica> bloquear(Long id, Long cia);
}
