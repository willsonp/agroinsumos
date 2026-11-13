package com.agroinsumos.backend.cxc;

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
@Table(name = "recibo")
public class Recibo extends Auditable {
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
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "sesion_caja_id")
    private Long sesionCajaId;
    @Column(length = 200)
    private String concepto;
    @Column(name = "total_aplicado", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAplicado;
    @Column(name = "total_retenciones", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalRetenciones = BigDecimal.ZERO;
    @Column(name = "total_recibido", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalRecibido;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal devuelta = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.EMITIDO;
    @Column(name = "anulado_en")
    private Instant anuladoEn;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    /** Balance pendiente total del cliente (facturas a crédito y notas de débito) después de este cobro. */
    @Column(name = "balance_cliente_despues", precision = 18, scale = 2)
    private BigDecimal balanceClienteDespues;
    /** VENTA: cobro de una venta de contado del punto de venta; COBRO: recibo de ingreso de CxC. */
    @Column(nullable = false, length = 5)
    private String origen = "COBRO";
    @Version
    private long version;

    @OneToMany(mappedBy = "recibo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ReciboAplicacion> aplicaciones = new ArrayList<>();

    @OneToMany(mappedBy = "recibo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ReciboPago> pagos = new ArrayList<>();
}
