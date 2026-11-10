package com.agroinsumos.backend.inventario.documento;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "documento_inventario_linea")
public class DocumentoInventarioLinea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id")
    private DocumentoInventario documento;
    @Column(name = "articulo_id", nullable = false)
    private Long articuloId;
    /** ENTRADA/SALIDA/TRANSFERENCIA: cantidad a mover. AJUSTE: conteo físico. */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal cantidad;
    /** AJUSTE: existencia del sistema al momento de aplicar (legacy "Teorico"). */
    @Column(name = "cantidad_teorica", precision = 14, scale = 3)
    private BigDecimal cantidadTeorica;
    @Column(name = "costo_unitario", precision = 18, scale = 4)
    private BigDecimal costoUnitario;
}
