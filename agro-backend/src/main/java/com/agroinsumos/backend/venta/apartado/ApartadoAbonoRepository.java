package com.agroinsumos.backend.venta.apartado;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApartadoAbonoRepository extends JpaRepository<ApartadoAbono, Long> {
    List<ApartadoAbono> findByApartadoIdOrderById(Long apartadoId);
}
