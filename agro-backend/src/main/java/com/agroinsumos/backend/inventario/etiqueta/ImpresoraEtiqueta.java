package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.crud.Catalogo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Impresora de etiquetas ZPL en red (puerto RAW 9100). */
@Getter
@Setter
@Entity
@Table(name = "impresora_etiqueta")
public class ImpresoraEtiqueta extends Auditable implements Catalogo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false, length = 60)
    private String nombre;
    @Column(nullable = false, length = 100)
    private String host;
    @Column(nullable = false)
    private int puerto = 9100;
    @Column(nullable = false)
    private int dpi = 203;
    @Column(name = "ancho_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal anchoMm = new BigDecimal("50.8");
    @Column(name = "alto_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal altoMm = new BigDecimal("25.4");
    @Column(nullable = false, length = 1)
    private String estado = "A";
}
