package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Cuenta del catálogo contable (legacy catalogo). */
@Getter
@Setter
@Entity
@Table(name = "cuenta_contable")
public class CuentaContable extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 14)
    private String codigo;
    @Column(nullable = false, length = 100)
    private String nombre;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TipoCuenta tipo;
    @Column(name = "padre_id")
    private Long padreId;
    @Column(nullable = false)
    private int nivel = 1;
    @Column(name = "acepta_movimiento", nullable = false)
    private boolean aceptaMovimiento = true;
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
