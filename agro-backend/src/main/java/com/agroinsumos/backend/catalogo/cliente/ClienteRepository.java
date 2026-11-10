package com.agroinsumos.backend.catalogo.cliente;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    @Query("""
            select e from Cliente e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', coalesce(e.rncCedula,''), ' ', coalesce(e.telefono,'')))
                   like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Cliente> buscar(Long companiaId, String q, Pageable pageable);

    boolean existsByCompaniaIdAndRncCedula(Long companiaId, String rncCedula);

    /** Balance pendiente (CxC) del cliente: reemplaza el proc. Consulta_BalanceCliente. */
    @Query(value = "select coalesce(sum(balance),0) from factura where cliente_id = :clienteId and condicion = 'CREDITO' and estado <> 'ANULADA'",
            nativeQuery = true)
    BigDecimal balancePendiente(Long clienteId);
}
