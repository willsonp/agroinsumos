package com.agroinsumos.backend.venta.factura;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "factura")
public class Factura extends Auditable {
    /** Legacy TipoFactura: A = contado, E = crédito. */
    public enum Condicion { CONTADO, CREDITO }
    public enum Estado { EMITIDA, PAGADA, ANULADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Condicion condicion;
    @Column(name = "dias_credito", nullable = false)
    private int diasCredito;
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "cliente_nombre", nullable = false, length = 150)
    private String clienteNombre;
    @Column(name = "cliente_rnc", length = 11)
    private String clienteRnc;
    @Column(name = "vendedor_id")
    private Long vendedorId;
    @Column(name = "almacen_id", nullable = false)
    private Long almacenId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_precio", nullable = false, length = 10)
    private TipoPrecio tipoPrecio;
    @Column(length = 600)
    private String comentario;
    @Column(name = "numero_orden", length = 30)
    private String numeroOrden;
    @Column(name = "monto_gravado", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoGravado = BigDecimal.ZERO;
    @Column(name = "monto_exento", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoExento = BigDecimal.ZERO;
    @Column(name = "total_descuento", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDescuento = BigDecimal.ZERO;
    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;
    @Column(name = "costo_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal costoTotal = BigDecimal.ZERO;
    @Column(name = "tipo_ecf")
    private Short tipoEcf;
    @Column(length = 13)
    private String encf;
    @Column(name = "ecf_documento_id")
    private Long ecfDocumentoId;
    @Column(name = "ncf_legacy", length = 19)
    private String ncfLegacy;
    @Column(name = "desde_conduce", nullable = false)
    private boolean desdeConduce;
    @Column(name = "cotizacion_id")
    private Long cotizacionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Estado estado = Estado.EMITIDA;
    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn = Instant.now();
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Version
    private long version;

    @OneToMany(mappedBy = "factura", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<FacturaLinea> lineas = new ArrayList<>();

    /** Comprobante a referenciar en notas de crédito/débito (e-NCF o NCF legacy de facturas migradas). */
    public String comprobante() { return encf != null ? encf : ncfLegacy; }
}
