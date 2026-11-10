package com.agroinsumos.backend.venta.cotizacion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CotizacionRepository extends JpaRepository<Cotizacion, Long> {
    Optional<Cotizacion> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("""
            select c from Cotizacion c where c.companiaId = :companiaId
              and (:clienteId is null or c.clienteId = :clienteId) and (:estado is null or c.estado = :estado)
            """)
    Page<Cotizacion> buscar(Long companiaId, Long clienteId, Cotizacion.Estado estado, Pageable p);
}
