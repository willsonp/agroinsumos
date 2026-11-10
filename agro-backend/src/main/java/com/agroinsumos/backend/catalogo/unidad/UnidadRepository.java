package com.agroinsumos.backend.catalogo.unidad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnidadRepository extends JpaRepository<Unidad, Long> {
    @Query("select e from Unidad e where :q is null or lower(e.nombre) like lower(concat('%', cast(:q as string), '%'))")
    Page<Unidad> buscar(String q, Pageable pageable);

    boolean existsByNombreIgnoreCase(String nombre);
}
