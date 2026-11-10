package com.agroinsumos.backend.inventario.kardex;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Línea del kardex. Inmutable: las correcciones se hacen con un movimiento inverso. */
@Getter
@Setter
@Entity
@Table(name = "inventario_movimiento")
public class MovimientoInventario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false)
    private Long companiaId;
    @Column(name = "articulo_id", nullable = false)
    private Long articuloId;
    @Column(name = "almacen_id", nullable = false)
    private Long almacenId;
    @Column(nullable = false)
    private Instant fecha = Instant.now();
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TipoMovimiento tipo;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    @Column(name = "costo_unitario", nullable = false, precision = 18, scale = 4)
    private BigDecimal costoUnitario;
    @Column(name = "existencia_final", nullable = false, precision = 14, scale = 3)
    private BigDecimal existenciaFinal;
    @Column(name = "documento_tipo", nullable = false, length = 30)
    private String documentoTipo;
    @Column(name = "documento_id", nullable = false)
    private Long documentoId;
    @Column(length = 45)
    private String usuario;
}
