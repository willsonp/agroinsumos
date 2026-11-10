package com.agroinsumos.backend.banco;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CuentaBancariaRepository extends JpaRepository<CuentaBancaria, Long> {
    @Query("""
            select e from CuentaBancaria e where e.companiaId = :companiaId
              and (:q is null or lower(concat(e.nombre, ' ', e.numero)) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<CuentaBancaria> buscar(Long companiaId, String q, Pageable pageable);

    boolean existsByCompaniaIdAndBancoIdAndNumero(Long companiaId, Long bancoId, String numero);

    /** Bloquea la cuenta: serializa numeración de cheques y control de saldo entre usuarios concurrentes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CuentaBancaria c where c.id = :id and c.companiaId = :companiaId")
    Optional<CuentaBancaria> bloquear(Long id, Long companiaId);
}
