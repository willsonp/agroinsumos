package com.agroinsumos.backend.cxc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "recibo_aplicacion")
public class ReciboAplicacion {
    public enum TipoDocumento { FACTURA, NOTA_DEBITO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recibo_id")
    private Recibo recibo;
    @Enumerated(EnumType.STRING)
    @Column(name = "documento_tipo", nullable = false, length = 12)
    private TipoDocumento documentoTipo;
    @Column(name = "documento_id", nullable = false)
    private Long documentoId;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(name = "retencion_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal retencionItbis = BigDecimal.ZERO;
    @Column(name = "retencion_isr", nullable = false, precision = 18, scale = 2)
    private BigDecimal retencionIsr = BigDecimal.ZERO;
    /** Balance que quedó en el documento después de este abono. */
    @Column(name = "balance_despues", precision = 18, scale = 2)
    private BigDecimal balanceDespues;

    /** Lo que se descuenta del balance del documento. */
    public BigDecimal totalAbonado() { return monto.add(retencionItbis).add(retencionIsr); }
}
