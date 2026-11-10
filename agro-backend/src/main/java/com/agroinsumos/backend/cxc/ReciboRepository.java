package com.agroinsumos.backend.cxc;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface ReciboRepository extends JpaRepository<Recibo, Long> {
    Optional<Recibo> findByIdAndCompaniaId(Long id, Long companiaId);

    /** Reemplaza Consulta_ReciboPorCliente / Consulta_ReciboPorFecha / Consulta_PorNumeroRecibo. */
    @Query("""
            select r from Recibo r where r.companiaId = :companiaId
              and (:clienteId is null or r.clienteId = :clienteId)
              and (cast(:desde as date) is null or r.fecha >= :desde)
              and (cast(:hasta as date) is null or r.fecha <= :hasta)
              and (:numero is null or r.numero = :numero)
            """)
    Page<Recibo> buscar(Long companiaId, Long clienteId, LocalDate desde, LocalDate hasta, Long numero, Pageable p);
}
