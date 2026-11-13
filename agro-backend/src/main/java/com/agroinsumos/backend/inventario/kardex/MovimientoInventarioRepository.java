package com.agroinsumos.backend.inventario.kardex;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, Long> {

    /** ¿El artículo ya recibió compras o saldo inicial? (desde entonces el costo promedio lo calculan las compras) */
    @Query(value = "select exists (select 1 from inventario_movimiento where articulo_id = :articuloId and tipo in ('COMPRA', 'SALDO_INICIAL'))",
            nativeQuery = true)
    boolean tieneCompras(Long articuloId);

    @Query("""
            select m from MovimientoInventario m
            where m.articuloId = :articuloId and (:almacenId is null or m.almacenId = :almacenId)
            order by m.id desc
            """)
    Page<MovimientoInventario> kardex(Long articuloId, Long almacenId, Pageable pageable);

    List<MovimientoInventario> findByDocumentoTipoAndDocumentoId(String documentoTipo, Long documentoId);
}
