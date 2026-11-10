package com.agroinsumos.backend.catalogo.ciudad;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CiudadRepository extends JpaRepository<Ciudad, Long> {
    @Query("select e from Ciudad e where :q is null or lower(e.nombre) like lower(concat('%', cast(:q as string), '%'))")
    Page<Ciudad> buscar(String q, Pageable pageable);

    boolean existsByNombreIgnoreCase(String nombre);
}
