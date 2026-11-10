package com.agroinsumos.backend.venta.devolucion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "devolucion_linea")
public class DevolucionLinea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "devolucion_id")
    private Devolucion devolucion;
    @Column(name = "factura_linea_id", nullable = false)
    private Long facturaLineaId;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal importe;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis;
    /** false = mercancía dañada: se acredita al cliente pero no vuelve al inventario. */
    @Column(name = "reingresa_inventario", nullable = false)
    private boolean reingresaInventario = true;
}
