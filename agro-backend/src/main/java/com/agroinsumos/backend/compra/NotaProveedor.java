package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Nota de crédito o débito recibida del suplidor sobre una compra (legacy notacreditocxp / notadebitocxp). */
@Getter
@Setter
@Entity
@Table(name = "nota_proveedor")
public class NotaProveedor extends Auditable {
    public enum Tipo { CREDITO, DEBITO }
    public enum Estado { EMITIDA, ANULADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 7)
    private Tipo tipo;
    @Column(name = "proveedor_id", nullable = false)
    private Long proveedorId;
    @Column(name = "compra_id", nullable = false)
    private Long compraId;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(length = 19)
    private String ncf;
    @Column(nullable = false, length = 200)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Column(name = "devuelve_inventario", nullable = false)
    private boolean devuelveInventario;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Estado estado = Estado.EMITIDA;
    @Column(name = "anulado_en")
    private Instant anuladoEn;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;

    @OneToMany(mappedBy = "nota", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Linea> lineas = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "nota_proveedor_linea")
    public static class Linea {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "nota_id")
        private NotaProveedor nota;
        @Column(name = "compra_linea_id", nullable = false)
        private Long compraLineaId;
        @Column(name = "articulo_id", nullable = false)
        private Long articuloId;
        @Column(nullable = false, precision = 14, scale = 3)
        private BigDecimal cantidad;
        /** Unidades base por unidad de la línea (presentación). */
        @Column(nullable = false, precision = 14, scale = 3)
        private BigDecimal factor = BigDecimal.ONE;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal importe;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal itbis;
        @Column(name = "costo_unitario", nullable = false, precision = 18, scale = 4)
        private BigDecimal costoUnitario;
    }
}
