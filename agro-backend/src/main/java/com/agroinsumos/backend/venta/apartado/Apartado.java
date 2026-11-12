package com.agroinsumos.backend.venta.apartado;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Apartado: el cliente separa mercancía y la va abonando (legacy apartado). */
@Getter
@Setter
@Entity
@Table(name = "apartado")
public class Apartado extends Auditable {
    public enum Estado { ABIERTO, FACTURADO, CANCELADO }

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
    @Column(nullable = false, length = 255)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal abonado = BigDecimal.ZERO;
    @Column(name = "fecha_limite")
    private LocalDate fechaLimite;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.ABIERTO;
    @Column(name = "factura_id")
    private Long facturaId;
    @Column(name = "motivo_cancelacion", length = 200)
    private String motivoCancelacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();
    @Version
    private long version;

    @OneToMany(mappedBy = "apartado", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<Linea> lineas = new ArrayList<>();

    public BigDecimal pendiente() { return total.subtract(abonado).max(BigDecimal.ZERO); }

    @Getter
    @Setter
    @Entity
    @Table(name = "apartado_linea")
    public static class Linea {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "apartado_id")
        private Apartado apartado;
        @Column(nullable = false)
        private int linea;
        @Column(name = "articulo_id", nullable = false)
        private Long articuloId;
        @Column(nullable = false, precision = 14, scale = 3)
        private BigDecimal cantidad;
        /** Unidades base por unidad de la línea (presentación). */
        @Column(nullable = false, precision = 14, scale = 3)
        private BigDecimal factor = BigDecimal.ONE;
        @Column(name = "precio_unitario", nullable = false, precision = 18, scale = 4)
        private BigDecimal precioUnitario;
        @Column(name = "descuento_porcentaje", nullable = false, precision = 5, scale = 2)
        private BigDecimal descuentoPorcentaje = BigDecimal.ZERO;
    }
}
