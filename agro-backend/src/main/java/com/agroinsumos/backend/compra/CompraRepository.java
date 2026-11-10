package com.agroinsumos.backend.compra;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CompraRepository extends JpaRepository<Compra, Long> {
    Optional<Compra> findByIdAndCompaniaId(Long id, Long companiaId);

    boolean existsByProveedorIdAndNcfProveedorAndEstadoNot(Long proveedorId, String ncf, Compra.Estado estado);

    @Query("""
            select c from Compra c where c.companiaId = :companiaId
              and (:proveedorId is null or c.proveedorId = :proveedorId)
              and (:estado is null or c.estado = :estado)
              and (cast(:desde as date) is null or c.fecha >= :desde)
              and (cast(:hasta as date) is null or c.fecha <= :hasta)
            """)
    Page<Compra> buscar(Long companiaId, Long proveedorId, Compra.Estado estado, LocalDate desde, LocalDate hasta, Pageable p);

    @Query("select c from Compra c where c.proveedorId = :proveedorId and c.balance > 0 and c.estado <> 'ANULADA' order by c.fechaVencimiento, c.id")
    List<Compra> pendientes(Long proveedorId);
}
