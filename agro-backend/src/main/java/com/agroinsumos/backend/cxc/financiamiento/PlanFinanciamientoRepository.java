package com.agroinsumos.backend.cxc.financiamiento;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlanFinanciamientoRepository extends JpaRepository<PlanFinanciamiento, Long> {
    Optional<PlanFinanciamiento> findByIdAndCompaniaId(Long id, Long companiaId);

    boolean existsByFacturaIdAndEstado(Long facturaId, PlanFinanciamiento.Estado estado);

    List<PlanFinanciamiento> findByCompaniaIdAndEstado(Long companiaId, PlanFinanciamiento.Estado estado);

    @Query("""
            select p from PlanFinanciamiento p where p.companiaId = :cia and (:clienteId is null or p.clienteId = :clienteId)
              and (:estado is null or p.estado = :estado)
            """)
    Page<PlanFinanciamiento> buscar(Long cia, Long clienteId, PlanFinanciamiento.Estado estado, Pageable pageable);
}
