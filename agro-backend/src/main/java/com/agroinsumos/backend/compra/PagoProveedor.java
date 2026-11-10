package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.common.audit.Auditable;
import com.agroinsumos.backend.common.pago.FormaPago;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pago a suplidor (legacy pagocompra / deta_pago_compra). */
@Getter
@Setter
@Entity
@Table(name = "pago_proveedor")
public class PagoProveedor extends Auditable {
    public enum Estado { EMITIDO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "proveedor_id", nullable = false)
    private Long proveedorId;
    @Column(name = "sesion_caja_id")
    private Long sesionCajaId;
    @Column(length = 200)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.EMITIDO;
    @Column(name = "anulado_en")
    private Instant anuladoEn;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    /** Moneda de las compras pagadas; total, aplicaciones y formas están en ella. */
    @Column(nullable = false, length = 3)
    private String moneda = "DOP";
    @Column(name = "tasa_cambio", nullable = false, precision = 12, scale = 4)
    private BigDecimal tasaCambio = BigDecimal.ONE;
    /** Pesos pagados − pesos históricos de lo aplicado (positivo = pérdida cambiaria). */
    @Column(name = "diferencia_cambiaria", nullable = false, precision = 18, scale = 2)
    private BigDecimal diferenciaCambiaria = BigDecimal.ZERO;

    @OneToMany(mappedBy = "pago", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Aplicacion> aplicaciones = new ArrayList<>();

    @OneToMany(mappedBy = "pago", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Forma> formas = new ArrayList<>();

    @Getter
    @Setter
    @Entity
    @Table(name = "pago_proveedor_aplicacion")
    public static class Aplicacion {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "pago_id")
        private PagoProveedor pago;
        @Column(name = "compra_id", nullable = false)
        private Long compraId;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal monto;
        /** Pesos que rebaja del balance de la compra (a la tasa de la compra). */
        @Column(name = "monto_dop", nullable = false, precision = 18, scale = 2)
        private BigDecimal montoDop;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "pago_proveedor_forma")
    public static class Forma {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "pago_id")
        private PagoProveedor pago;
        @Enumerated(EnumType.STRING)
        @Column(name = "forma_pago", nullable = false, length = 15)
        private FormaPago formaPago;
        @Column(nullable = false, precision = 18, scale = 2)
        private BigDecimal monto;
        @Column(length = 40)
        private String referencia;
        @Column(length = 45)
        private String banco;
        @Column(name = "cuenta_bancaria_id")
        private Long cuentaBancariaId;
    }
}
