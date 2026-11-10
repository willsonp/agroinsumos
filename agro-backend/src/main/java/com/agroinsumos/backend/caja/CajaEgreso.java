package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "caja_egreso")
public class CajaEgreso extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(name = "sesion_id", nullable = false)
    private Long sesionId;
    @Column(nullable = false)
    private LocalDate fecha;
    @Column(nullable = false, length = 100)
    private String beneficiario;
    @Column(length = 11)
    private String rnc;
    @Column(length = 19)
    private String ncf;
    @Column(nullable = false, length = 200)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal itbis = BigDecimal.ZERO;
    /** 606 campo 3 (por defecto 02: trabajos, suministros y servicios). */
    @Column(name = "tipo_bien_servicio", nullable = false, length = 2)
    private String tipoBienServicio = "02";
    /** e-CF 43 (gastos menores) emitido por nosotros cuando el proveedor no entrega comprobante. */
    @Column(name = "ecf_documento_id")
    private Long ecfDocumentoId;
    @Column(name = "usuario_id")
    private Long usuarioId;
}
