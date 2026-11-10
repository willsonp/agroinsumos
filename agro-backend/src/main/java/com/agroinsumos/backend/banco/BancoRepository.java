package com.agroinsumos.backend.banco;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BancoRepository extends JpaRepository<Banco, Long> {
    @Query("select e from Banco e where :q is null or lower(e.nombre) like lower(concat('%', cast(:q as string), '%'))")
    Page<Banco> buscar(String q, Pageable pageable);

    boolean existsByNombreIgnoreCase(String nombre);
}
