package com.agroinsumos.backend.ecf.secuencia;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Rango de e-NCF autorizado por la DGII (sustituye a la tabla legacy tablancf). */
@Getter
@Setter
@Entity
@Table(name = "ecf_secuencia")
public class EcfSecuencia extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compania_id", nullable = false)
    private Long companiaId;

    @Column(name = "tipo_ecf", nullable = false)
    private short tipoEcf;
    /** 'E' = e-NCF (E310000000001), 'B' = NCF tradicional (B0100000001). */
    @Column(nullable = false, length = 1)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String serie = "E";

    @Column(name = "secuencia_desde", nullable = false)
    private long secuenciaDesde;

    @Column(name = "secuencia_hasta", nullable = false)
    private long secuenciaHasta;

    @Column(name = "ultima_usada", nullable = false)
    private long ultimaUsada;

    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;

    @Column(nullable = false)
    private boolean activa = true;

    @Version
    private long version;

    public long disponibles() {
        return secuenciaHasta - Math.max(ultimaUsada, secuenciaDesde - 1);
    }
}
