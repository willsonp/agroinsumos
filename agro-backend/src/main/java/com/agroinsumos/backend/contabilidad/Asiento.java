package com.agroinsumos.backend.contabilidad;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Asiento de diario (legacy entradadiario / cntcontabilidad). */
@Getter
@Setter
@Entity
@Table(name = "asiento")
public class Asiento {
    public enum Tipo { MANUAL, AUTOMATICO }
    public enum Estado { REGISTRADO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;
    @Column(name = "origen_tipo", length = 25)
    private String origenTipo;
    @Column(name = "origen_id")
    private Long origenId;
    @Column(nullable = false)
    private boolean reverso;
    @Column(nullable = false, length = 200)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.REGISTRADO;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @OneToMany(mappedBy = "asiento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<Linea> lineas = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "asiento_linea")
    public static class Linea {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "asiento_id")
        private Asiento asiento;
        @Column(nullable = false)
        private int linea;
        @Column(name = "cuenta_id", nullable = false)
        private Long cuentaId;
        @Column(length = 200)
        private String descripcion;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal debito = BigDecimal.ZERO;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal credito = BigDecimal.ZERO;
        @Column(name = "tercero_tipo", length = 1)
        private String terceroTipo;
        @Column(name = "tercero_id")
        private Long terceroId;
    }
}
