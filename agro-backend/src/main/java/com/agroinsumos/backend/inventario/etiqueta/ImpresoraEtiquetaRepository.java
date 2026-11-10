package com.agroinsumos.backend.inventario.etiqueta;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ImpresoraEtiquetaRepository extends JpaRepository<ImpresoraEtiqueta, Long> {
    @Query("select i from ImpresoraEtiqueta i where i.companiaId = :cia and (:q is null or lower(i.nombre) like lower(concat('%', cast(:q as string), '%')))")
    Page<ImpresoraEtiqueta> buscar(Long cia, String q, Pageable pageable);

    boolean existsByCompaniaIdAndNombreIgnoreCase(Long companiaId, String nombre);
}
