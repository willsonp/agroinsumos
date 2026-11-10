package com.agroinsumos.backend.venta.conduce;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConduceRepository extends JpaRepository<Conduce, Long> {
    Optional<Conduce> findByIdAndCompaniaId(Long id, Long companiaId);

    List<Conduce> findByIdInAndCompaniaId(List<Long> ids, Long companiaId);

    @Query("""
            select c from Conduce c where c.companiaId = :cia and (:clienteId is null or c.clienteId = :clienteId)
              and (:estado is null or c.estado = :estado)
            """)
    Page<Conduce> buscar(Long cia, Long clienteId, Conduce.Estado estado, Pageable pageable);
}
