package com.agroinsumos.backend.venta.factura;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface FacturaRepository extends JpaRepository<Factura, Long> {

    Optional<Factura> findByIdAndCompaniaId(Long id, Long companiaId);

    /** Reemplaza ConsultaFactura_PorCliente / _PorFecha / _PorNumero. */
    @Query("""
            select f from Factura f where f.companiaId = :companiaId
              and (:clienteId is null or f.clienteId = :clienteId)
              and (:estado is null or f.estado = :estado)
              and (cast(:desde as date) is null or f.fecha >= :desde)
              and (cast(:hasta as date) is null or f.fecha <= :hasta)
              and (:numero is null or f.numero = :numero)
            """)
    Page<Factura> buscar(Long companiaId, Long clienteId, Factura.Estado estado, LocalDate desde, LocalDate hasta,
                         Long numero, Pageable pageable);
}
