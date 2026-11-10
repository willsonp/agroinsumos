package com.agroinsumos.backend.caja.chica;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "caja_chica_movimiento")
public class CajaChicaMovimiento {
    public enum Tipo { APERTURA, GASTO, REPOSICION }
    public enum Estado { REGISTRADO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false)
    private Long companiaId;
    @Column(name = "caja_chica_id", nullable = false)
    private Long cajaChicaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;
    @Column(nullable = false, length = 200)
    private String concepto;
    @Column(length = 100)
    private String beneficiario;
    @Column(length = 11)
    private String rnc;
    @Column(length = 19)
    private String ncf;
    @Column(name = "tipo_bien_servicio", length = 2)
    private String tipoBienServicio;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis = BigDecimal.ZERO;
    @Column(name = "cuenta_gasto", length = 14)
    private String cuentaGasto;
    @Column(length = 15)
    private String forma;
    @Column(name = "cuenta_bancaria_id")
    private Long cuentaBancariaId;
    @Column(name = "sesion_caja_id")
    private Long sesionCajaId;
    @Column(name = "reposicion_id")
    private Long reposicionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.REGISTRADO;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();
}
