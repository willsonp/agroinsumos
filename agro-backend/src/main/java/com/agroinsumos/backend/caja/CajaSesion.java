package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "caja_sesion")
public class CajaSesion extends Auditable {
    public enum Estado { ABIERTA, CERRADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "compania_id", nullable = false, updatable = false)
    private Long companiaId;
    @Column(nullable = false)
    private long numero;
    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;
    @Column(name = "turno_id")
    private Long turnoId;
    @Column(name = "abierta_en", nullable = false)
    private Instant abiertaEn = Instant.now();
    @Column(name = "monto_inicial", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoInicial = BigDecimal.ZERO;
    @Column(name = "cerrada_en")
    private Instant cerradaEn;
    @Column(name = "efectivo_esperado", precision = 18, scale = 2)
    private BigDecimal efectivoEsperado;
    @Column(name = "efectivo_contado", precision = 18, scale = 2)
    private BigDecimal efectivoContado;
    @Column(precision = 18, scale = 2)
    private BigDecimal diferencia;
    @Column(length = 200)
    private String observacion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Estado estado = Estado.ABIERTA;
    @Version
    private long version;

    public boolean abierta() { return estado == Estado.ABIERTA; }
}
