package com.agroinsumos.backend.compra;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "compra_linea")
public class CompraLinea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compra_id")
    private Compra compra;
    @Column(nullable = false)
    private int linea;
    @Column(name = "articulo_id", nullable = false)
    private Long articuloId;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    @Column(name = "costo_unitario", nullable = false, precision = 18, scale = 4)
    private BigDecimal costoUnitario;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal descuento = BigDecimal.ZERO;
    @Column(name = "tasa_itbis", nullable = false, precision = 5, scale = 2)
    private BigDecimal tasaItbis;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal importe;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis;
    /** Costo unitario que entra al inventario: (importe + flete prorrateado) / cantidad. El ITBIS es adelantado, no costo. */
    @Column(name = "costo_final", nullable = false, precision = 18, scale = 4)
    private BigDecimal costoFinal;
}
