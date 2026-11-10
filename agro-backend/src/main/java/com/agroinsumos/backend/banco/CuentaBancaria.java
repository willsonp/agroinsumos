package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cuenta bancaria de la compañía (legacy detallebanco). */
@Getter
@Setter
@Entity
@Table(name = "cuenta_bancaria")
public class CuentaBancaria extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(name = "banco_id", nullable = false)
    private Long bancoId;
    @Column(nullable = false, length = 30)
    private String numero;
    @Column(nullable = false, length = 60)
    private String nombre;
    @Column(nullable = false, length = 10)
    private String tipo = "CORRIENTE";
    @Column(nullable = false, length = 3)
    private String moneda = "DOP";
    @Column(name = "saldo_inicial", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoInicial = BigDecimal.ZERO;
    @Column(name = "fecha_saldo_inicial", nullable = false)
    private LocalDate fechaSaldoInicial;
    @Column(name = "cuenta_contable", length = 12)
    private String cuentaContable;
    @Column(name = "proximo_cheque", nullable = false)
    private long proximoCheque = 1;
    @Column(nullable = false, length = 1)
    private String estado = "A";
    @Version
    private long version;
}
