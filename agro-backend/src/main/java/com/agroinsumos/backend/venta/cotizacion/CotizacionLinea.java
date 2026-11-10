package com.agroinsumos.backend.venta.cotizacion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "cotizacion_linea")
public class CotizacionLinea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cotizacion_id")
    private Cotizacion cotizacion;
    @Column(nullable = false)
    private int linea;
    @Column(name = "articulo_id", nullable = false)
    private Long articuloId;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    @Column(name = "precio_unitario", nullable = false, precision = 18, scale = 4)
    private BigDecimal precioUnitario;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal descuento;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal importe;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis;
}
