package com.agroinsumos.backend.venta.devolucion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DevolucionRepository extends JpaRepository<Devolucion, Long> {
    Optional<Devolucion> findByIdAndCompaniaId(Long id, Long companiaId);

    Page<Devolucion> findByCompaniaId(Long companiaId, Pageable pageable);

    List<Devolucion> findByFacturaIdOrderById(Long facturaId);
}
