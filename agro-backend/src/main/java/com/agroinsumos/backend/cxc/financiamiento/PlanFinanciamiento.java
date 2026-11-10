package com.agroinsumos.backend.cxc.financiamiento;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Venta financiada: plan de cuotas sobre una factura a crédito (legacy facturapro financiada + cuotas). */
@Getter
@Setter
@Entity
@Table(name = "plan_financiamiento")
public class PlanFinanciamiento {
    public enum Estado { ACTIVO, CANCELADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "factura_id", nullable = false)
    private Long facturaId;
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "pagado_previo", nullable = false, precision = 18, scale = 2)
    private BigDecimal pagadoPrevio = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal capital;
    @Column(name = "tasa_interes", nullable = false, precision = 6, scale = 3)
    private BigDecimal tasaInteres = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal interes = BigDecimal.ZERO;
    @Column(name = "nota_debito_id")
    private Long notaDebitoId;
    @Column(name = "cantidad_cuotas", nullable = false)
    private int cantidadCuotas;
    @Column(name = "frecuencia_dias", nullable = false)
    private int frecuenciaDias;
    @Column(name = "mora_porcentaje", nullable = false, precision = 6, scale = 3)
    private BigDecimal moraPorcentaje = BigDecimal.ZERO;
    @Column(name = "dias_gracia", nullable = false)
    private int diasGracia;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.ACTIVO;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("numero")
    private List<Cuota> cuotas = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "plan_cuota")
    public static class Cuota {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "plan_id")
        private PlanFinanciamiento plan;
        @Column(nullable = false)
        private int numero;
        @Column(nullable = false)
        private LocalDate vencimiento;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal monto;
        @Column(name = "mora_nota_debito_id")
        private Long moraNotaDebitoId;
    }
}
