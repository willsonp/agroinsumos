package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cargo adicional al cliente (intereses, mora, diferencia de precio). Emite e-CF 33. */
@Getter
@Setter
@Entity
@Table(name = "nota_debito")
public class NotaDebito extends Auditable {
    public enum Estado { EMITIDA, PAGADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "factura_id", nullable = false)
    private Long facturaId;
    @Column(nullable = false, length = 90)
    private String concepto;
    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;
    @Column(length = 13)
    private String encf;
    @Column(name = "ecf_documento_id")
    private Long ecfDocumentoId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.EMITIDA;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Version
    private long version;
}
