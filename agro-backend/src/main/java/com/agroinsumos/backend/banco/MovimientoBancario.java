package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "movimiento_bancario")
public class MovimientoBancario extends Auditable {
    public enum Estado { REGISTRADO, ANULADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(name = "cuenta_id", nullable = false)
    private Long cuentaId;
    @Column(nullable = false)
    private long numero;
    @Column(nullable = false)
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TipoMovimientoBancario tipo;
    @Column(length = 40)
    private String referencia;
    @Column(length = 100)
    private String beneficiario;
    @Column(nullable = false, length = 200)
    private String concepto;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal monto;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.REGISTRADO;
    @Column(name = "conciliacion_id")
    private Long conciliacionId;
    @Column(name = "origen_tipo", length = 25)
    private String origenTipo;
    @Column(name = "origen_id")
    private Long origenId;
    @Column(name = "transferencia_par_id")
    private Long transferenciaParId;
    @Column(name = "anulado_en")
    private Instant anuladoEn;
    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;
    @Column(name = "usuario_id")
    private Long usuarioId;
    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn = Instant.now();
    @Version
    private long version;

    /** Monto con signo: + entrada, - salida. */
    public BigDecimal montoConSigno() { return tipo.entrada() ? monto : monto.negate(); }
}
