package com.agroinsumos.backend.catalogo.cliente;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "cliente")
public class Cliente extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 150)
    private String nombre;
    @Column(name = "rnc_cedula", length = 11)
    private String rncCedula;
    /** e-CF por defecto: 31 crédito fiscal, 32 consumo, 44 régimen especial, 45 gubernamental. */
    @Column(name = "tipo_ecf_preferido", nullable = false)
    private short tipoEcfPreferido = 32;
    @Column(name = "exento_itbis", nullable = false)
    private boolean exentoItbis;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_precio", nullable = false, length = 10)
    private TipoPrecio tipoPrecio = TipoPrecio.CONTADO;
    @Column(name = "limite_credito", nullable = false, precision = 18, scale = 2)
    private BigDecimal limiteCredito = BigDecimal.ZERO;
    @Column(name = "dias_credito", nullable = false)
    private int diasCredito;
    @Column(name = "ciudad_id")
    private Long ciudadId;
    @Column(name = "zona_id")
    private Long zonaId;
    @Column(length = 150)
    private String direccion;
    @Column(length = 100)
    private String correo;
    @Column(length = 20)
    private String telefono;
    @Column(length = 20)
    private String celular;
    @Column(length = 100)
    private String contacto;
    @Column(name = "cuenta_contable", length = 12)
    private String cuentaContable;
    @Column(name = "saldo_a_favor", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoAFavor = BigDecimal.ZERO;
    @Column(nullable = false, length = 1)
    private String estado = "A";
    @Version
    private long version;
}
