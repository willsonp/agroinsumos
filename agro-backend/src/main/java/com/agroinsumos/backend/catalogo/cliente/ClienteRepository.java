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

    /**
     * Lo que debe el cliente (CxC), lo mismo que muestra Cobros: facturas con balance (a crédito o de contado sin
     * cobrar) y notas de débito pendientes (mora, intereses). Reemplaza el proc. Consulta_BalanceCliente.
     */
    @Query(value = """
            select coalesce((select sum(balance) from factura where cliente_id = :clienteId and balance > 0 and estado <> 'ANULADA'), 0)
                 + coalesce((select sum(balance) from nota_debito where cliente_id = :clienteId and balance > 0), 0)""",
            nativeQuery = true)
    BigDecimal balancePendiente(Long clienteId);

    /** Bloquea la fila del cliente hasta el fin de la transacción (dos ventas a crédito simultáneas no pasan juntas el límite). */
    @Query(value = "select id from cliente where id = :clienteId for update", nativeQuery = true)
    Long bloquear(Long clienteId);
}
