package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Factura de compra a suplidor (legacy compra + facturacreditocxp). */
@Getter
@Setter
@Entity
@Table(name = "compra")
public class Compra extends Auditable {
    public enum Condicion { CONTADO, CREDITO }
    public enum Estado { REGISTRADA, PAGADA, ANULADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(name = "proveedor_id", nullable = false)
    private Long proveedorId;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "ncf_proveedor", length = 19)
    private String ncfProveedor;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Condicion condicion;
    @Column(name = "dias_credito", nullable = false)
    private int diasCredito;
    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;
    @Column(name = "almacen_id", nullable = false)
    private Long almacenId;
    @Column(name = "numero_orden", length = 30)
    private String numeroOrden;
    @Column(length = 300)
    private String comentario;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;
    @Column(name = "total_descuento", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDescuento = BigDecimal.ZERO;
    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal flete = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Column(name = "itbis_retenido", nullable = false, precision = 18, scale = 2)
    private BigDecimal itbisRetenido = BigDecimal.ZERO;
    @Column(name = "isr_retenido", nullable = false, precision = 18, scale = 2)
    private BigDecimal isrRetenido = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.REGISTRADA;
    /** 606 campo 3 (01..11). Por defecto 09: compras que forman parte del costo de venta. */
    @Column(name = "tipo_bien_servicio", nullable = false, length = 2)
    private String tipoBienServicio = "09";
    /** 606 campo 17 (01..08), obligatorio si hay ISR retenido. */
    @Column(name = "tipo_retencion_isr", length = 2)
    private String tipoRetencionIsr;
    /** e-CF 41 emitido por nosotros (suplidor informal). */
    @Column(name = "encf_compra", length = 13)
    private String encfCompra;
    @Column(name = "ecf_documento_id")
    private Long ecfDocumentoId;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Version
    private long version;

    /** Moneda del suplidor; los montos de la compra están en pesos a {@code tasaCambio}. */
    @Column(nullable = false, length = 3)
    private String moneda = "DOP";
    @Column(name = "tasa_cambio", nullable = false, precision = 12, scale = 4)
    private BigDecimal tasaCambio = BigDecimal.ONE;
    @Column(name = "total_moneda", precision = 18, scale = 2)
    private BigDecimal totalMoneda;
    @Column(name = "balance_moneda", precision = 18, scale = 2)
    private BigDecimal balanceMoneda;

    public boolean extranjera() { return !"DOP".equals(moneda); }

    @OneToMany(mappedBy = "compra", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<CompraLinea> lineas = new ArrayList<>();

    /** Comprobante que sustenta el gasto/costo en el 606: el del suplidor o nuestro e-CF 41. */
    public String comprobante606() { return ncfProveedor != null ? ncfProveedor : encfCompra; }

    /** Lo que efectivamente se paga al suplidor. */
    public BigDecimal aPagar() { return total.subtract(itbisRetenido).subtract(isrRetenido); }
}
