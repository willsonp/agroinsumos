package com.agroinsumos.backend.catalogo.turno;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TurnoRepository extends JpaRepository<Turno, Long> {
    @Query("select e from Turno e where :q is null or lower(e.nombre) like lower(concat('%', cast(:q as string), '%'))")
    Page<Turno> buscar(String q, Pageable pageable);

    boolean existsByNombreIgnoreCase(String nombre);
}
