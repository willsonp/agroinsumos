package com.agroinsumos.backend.catalogo.zona;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ZonaRepository extends JpaRepository<Zona, Long> {
    @Query("select e from Zona e where :q is null or lower(e.nombre) like lower(concat('%', cast(:q as string), '%'))")
    Page<Zona> buscar(String q, Pageable pageable);

    boolean existsByNombreIgnoreCase(String nombre);
}
