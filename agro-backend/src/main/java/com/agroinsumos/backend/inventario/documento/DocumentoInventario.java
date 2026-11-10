package com.agroinsumos.backend.inventario.documento;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ajuste (conteo físico), entrada, salida o transferencia entre almacenes.
 * Legacy: ajuste/detalle_ajuste (estado T -> A con Aplicar_Ajuste) y transentsalida.
 */
@Getter
@Setter
@Entity
@Table(name = "documento_inventario")
public class DocumentoInventario extends Auditable {
    public enum Tipo { AJUSTE, ENTRADA, SALIDA, TRANSFERENCIA }
    public enum Estado { BORRADOR, APLICADO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Tipo tipo;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "almacen_id", nullable = false)
    private Long almacenId;
    @Column(name = "almacen_destino_id")
    private Long almacenDestinoId;
    @Column(length = 200)
    private String descripcion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.BORRADOR;
    @Column(name = "aplicado_en")
    private Instant aplicadoEn;
    @Column(name = "aplicado_por", length = 45)
    private String aplicadoPor;
    @Version
    private long version;

    @OneToMany(mappedBy = "documento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<DocumentoInventarioLinea> lineas = new ArrayList<>();
}
