package com.agroinsumos.backend.ecf.secuencia;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EcfSecuenciaRepository extends JpaRepository<EcfSecuencia, Long> {

    /** SELECT ... FOR UPDATE: serializa la asignación de e-NCF entre cajas concurrentes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EcfSecuencia s where s.companiaId = :companiaId and s.serie = :serie and s.tipoEcf = :tipo and s.activa = true")
    Optional<EcfSecuencia> bloquearActiva(Long companiaId, String serie, short tipo);

    Optional<EcfSecuencia> findByCompaniaIdAndSerieAndTipoEcfAndActivaTrue(Long companiaId, String serie, short tipo);

    List<EcfSecuencia> findByCompaniaIdOrderByTipoEcfAscIdDesc(Long companiaId);
}
