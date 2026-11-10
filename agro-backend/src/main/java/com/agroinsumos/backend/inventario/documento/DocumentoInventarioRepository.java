package com.agroinsumos.backend.inventario.documento;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DocumentoInventarioRepository extends JpaRepository<DocumentoInventario, Long> {

    @Query("""
            select d from DocumentoInventario d where d.companiaId = :companiaId
              and (:tipo is null or d.tipo = :tipo) and (:estado is null or d.estado = :estado)
            """)
    Page<DocumentoInventario> buscar(Long companiaId, DocumentoInventario.Tipo tipo, DocumentoInventario.Estado estado,
                                     Pageable pageable);

    Optional<DocumentoInventario> findByIdAndCompaniaId(Long id, Long companiaId);
}
