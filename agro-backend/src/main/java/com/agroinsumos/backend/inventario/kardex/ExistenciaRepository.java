package com.agroinsumos.backend.inventario.kardex;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface ExistenciaRepository extends JpaRepository<Existencia, Existencia.Pk> {

    @Query("select coalesce(sum(e.cantidad), 0) from Existencia e where e.articuloId = :articuloId")
    BigDecimal totalArticulo(Long articuloId);

    List<Existencia> findByArticuloId(Long articuloId);
}
