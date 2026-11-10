package com.agroinsumos.backend.venta.cotizacion;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "cotizacion")
public class Cotizacion extends Auditable {
    public enum Estado { ABIERTA, FACTURADA, ANULADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(name = "valida_hasta")
    private LocalDate validaHasta;
    @Column(name = "cliente_id", nullable = false)
    private Long clienteId;
    @Column(name = "vendedor_id")
    private Long vendedorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_precio", nullable = false, length = 10)
    private TipoPrecio tipoPrecio;
    @Column(length = 600)
    private String comentario;
    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Estado estado = Estado.ABIERTA;
    @Column(name = "factura_id")
    private Long facturaId;
    @Version
    private long version;

    @OneToMany(mappedBy = "cotizacion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("linea")
    private List<CotizacionLinea> lineas = new ArrayList<>();
}
