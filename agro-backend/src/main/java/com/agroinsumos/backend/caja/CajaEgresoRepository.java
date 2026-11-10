package com.agroinsumos.backend.caja;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CajaEgresoRepository extends JpaRepository<CajaEgreso, Long> {
    List<CajaEgreso> findBySesionIdOrderById(Long sesionId);
}
