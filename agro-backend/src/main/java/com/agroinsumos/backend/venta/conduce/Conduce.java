package com.agroinsumos.backend.venta.conduce;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Conduce / nota de entrega (legacy facturapro con estado T = en tránsito, F = facturado). */
@Getter
@Setter
@Entity
@Table(name = "conduce")
public class Conduce extends Auditable {
    public enum Estado { ABIERTO, FACTURADO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "almacen_id", nullable = false)
    private Long almacenId;
    @Column(name = "vendedor_id")
    private Long vendedorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_precio", nullable = false, length = 10)
    private TipoPrecio tipoPrecio;
    @Column(length = 300)
    private String comentario;
    @Column(name = "lugar_entrega", length = 200)
    private String lugarEntrega;
    @Column(name = "total_estimado", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalEstimado = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.ABIERTO;
    @Column(name = "factura_id")
    private Long facturaId;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();
    @Version
    private long version;

    @OneToMany(mappedBy = "conduce", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<Linea> lineas = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "conduce_linea")
    public static class Linea {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "conduce_id")
        private Conduce conduce;
        @Column(nullable = false)
        private int linea;
        @Column(name = "articulo_id", nullable = false)
        private Long articuloId;
        @Column(nullable = false, precision = 14, scale = 3)
        private BigDecimal cantidad;
        @Column(name = "precio_unitario", nullable = false, precision = 18, scale = 4)
        private BigDecimal precioUnitario;
        @Column(name = "descuento_porcentaje", nullable = false, precision = 5, scale = 2)
        private BigDecimal descuentoPorcentaje = BigDecimal.ZERO;
        @Column(name = "costo_unitario", nullable = false, precision = 18, scale = 4)
        private BigDecimal costoUnitario = BigDecimal.ZERO;
    }
}
