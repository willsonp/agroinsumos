package com.agroinsumos.backend.compra;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Optional;

public interface NotaProveedorRepository extends JpaRepository<NotaProveedor, Long> {
    Optional<NotaProveedor> findByIdAndCompaniaId(Long id, Long companiaId);

    @Query("""
            select n from NotaProveedor n where n.companiaId = :cia
              and (:proveedorId is null or n.proveedorId = :proveedorId) and (:compraId is null or n.compraId = :compraId)
            """)
    Page<NotaProveedor> buscar(Long cia, Long proveedorId, Long compraId, Pageable pageable);

    boolean existsByCompraIdAndEstado(Long compraId, NotaProveedor.Estado estado);

    /** Cantidad ya devuelta de una línea de compra (notas vigentes). */
    @Query("""
            select coalesce(sum(l.cantidad), 0) from NotaProveedor n join n.lineas l
             where l.compraLineaId = :compraLineaId and n.estado = com.agroinsumos.backend.compra.NotaProveedor.Estado.EMITIDA
            """)
    BigDecimal cantidadDevuelta(Long compraLineaId);
}
