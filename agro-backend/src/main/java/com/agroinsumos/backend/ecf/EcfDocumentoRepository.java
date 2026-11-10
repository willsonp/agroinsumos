package com.agroinsumos.backend.ecf;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EcfDocumentoRepository extends JpaRepository<EcfDocumento, Long> {

    Optional<EcfDocumento> findByIdAndCompaniaId(Long id, Long companiaId);

    Page<EcfDocumento> findByCompaniaId(Long companiaId, Pageable pageable);

    Page<EcfDocumento> findByCompaniaIdAndEstado(Long companiaId, EstadoEcf estado, Pageable pageable);

    @Query("select d.id from EcfDocumento d where d.estado in :estados and d.intentosEnvio < :maxIntentos order by d.id")
    List<Long> idsPorEstado(Collection<EstadoEcf> estados, int maxIntentos, Pageable pageable);
}
