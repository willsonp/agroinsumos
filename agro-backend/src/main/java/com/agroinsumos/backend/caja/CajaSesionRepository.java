package com.agroinsumos.backend.caja;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CajaSesionRepository extends JpaRepository<CajaSesion, Long> {
    Optional<CajaSesion> findByUsuarioIdAndEstado(Long usuarioId, CajaSesion.Estado estado);

    Optional<CajaSesion> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("""
            select s from CajaSesion s where s.companiaId = :companiaId
              and (:usuarioId is null or s.usuarioId = :usuarioId) and (:estado is null or s.estado = :estado)
            """)
    Page<CajaSesion> buscar(Long companiaId, Long usuarioId, CajaSesion.Estado estado, Pageable p);
}
