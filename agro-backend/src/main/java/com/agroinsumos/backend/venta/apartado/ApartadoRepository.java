package com.agroinsumos.backend.venta.apartado;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ApartadoRepository extends JpaRepository<Apartado, Long> {
    Optional<Apartado> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("""
            select a from Apartado a where a.companiaId = :cia and (:clienteId is null or a.clienteId = :clienteId)
              and (:estado is null or a.estado = :estado)
            """)
    Page<Apartado> buscar(Long cia, Long clienteId, Apartado.Estado estado, Pageable pageable);
}
