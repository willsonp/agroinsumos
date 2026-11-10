package com.agroinsumos.backend.caja.chica;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Fondo fijo de caja chica (legacy cajachica / cajachicabalance). */
@Getter
@Setter
@Entity
@Table(name = "caja_chica")
public class CajaChica extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 60)
    private String nombre;
    @Column(name = "responsable_id")
    private Long responsableId;
    @Column(name = "monto_fondo", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoFondo;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal saldo = BigDecimal.ZERO;
    @Column(name = "cuenta_contable", length = 14)
    private String cuentaContable;
    @Column(nullable = false, length = 1)
    private String estado = "A";
    @Version
    private long version;

    /** Gastado y pendiente de reponer. */
    public BigDecimal porReponer() { return montoFondo.subtract(saldo).max(BigDecimal.ZERO); }
}
