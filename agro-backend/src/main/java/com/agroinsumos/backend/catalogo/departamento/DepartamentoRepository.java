package com.agroinsumos.backend.catalogo.departamento;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DepartamentoRepository extends JpaRepository<Departamento, Long> {
    @Query("""
            select e from Departamento e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, '')) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Departamento> buscar(Long companiaId, String q, Pageable pageable);

}
