package com.agroinsumos.backend.venta.factura;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "factura_linea")
public class FacturaLinea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "factura_id")
    private Factura factura;
    @Column(nullable = false)
    private int linea;
    @Column(name = "articulo_id", nullable = false)
    private Long articuloId;
    @Column(nullable = false, length = 80)
    private String descripcion;
    @Column(length = 20)
    private String unidad;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    /** Unidades base que equivale cada unidad de la línea (1 = unidad base; 100 = saco de 100 lb). */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal factor = BigDecimal.ONE;
    @Column(name = "precio_unitario", nullable = false, precision = 18, scale = 4)
    private BigDecimal precioUnitario;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal descuento;
    @Column(name = "indicador_itbis", nullable = false)
    private short indicadorItbis;
    @Column(name = "tasa_itbis", nullable = false, precision = 5, scale = 2)
    private BigDecimal tasaItbis;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal importe;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis;
    @Column(name = "costo_unitario", nullable = false, precision = 18, scale = 4)
    private BigDecimal costoUnitario;
    @Column(name = "cantidad_devuelta", nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidadDevuelta = BigDecimal.ZERO;

    public BigDecimal pendienteDevolver() { return cantidad.subtract(cantidadDevuelta); }
}
