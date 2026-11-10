package com.agroinsumos.backend.venta.devolucion;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Devolución de venta. Siempre emite una Nota de Crédito electrónica (e-CF 34). */
@Getter
@Setter
@Entity
@Table(name = "devolucion")
public class Devolucion extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "factura_id", nullable = false)
    private Long facturaId;
    @Column(nullable = false, length = 90)
    private String motivo;
    /** e-CF 34 CodigoModificacion: 1 anulación total, 3 corrección de montos (parcial). */
    @Column(name = "codigo_modificacion", nullable = false)
    private short codigoModificacion;
    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Column(name = "aplicado_a_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal aplicadoABalance = BigDecimal.ZERO;
    @Column(name = "saldo_a_favor", nullable = false, precision = 18, scale = 2)
    private BigDecimal saldoAFavor = BigDecimal.ZERO;
    @Column(length = 13)
    private String encf;
    @Column(name = "ecf_documento_id")
    private Long ecfDocumentoId;
    @Column(nullable = false, length = 10)
    private String estado = "EMITIDA";
    @Column(name = "usuario_id")
    private Long usuarioId;

    @OneToMany(mappedBy = "devolucion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<DevolucionLinea> lineas = new ArrayList<>();
}
