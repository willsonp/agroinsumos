package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.common.audit.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** e-CF emitido. El XML firmado es inmutable y debe conservarse (obligación de archivo fiscal). */
@Getter
@Setter
@Entity
@Table(name = "ecf_documento")
public class EcfDocumento extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compania_id", nullable = false)
    private Long companiaId;

    @Column(name = "tipo_ecf", nullable = false)
    private short tipoEcf;

    @Column(nullable = false, length = 13)
    private String encf;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate fechaEmision;

    @Column(name = "rnc_comprador", length = 11)
    private String rncComprador;

    @Column(name = "razon_social_comprador", length = 150)
    private String razonSocialComprador;

    @Column(name = "monto_gravado", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoGravado;

    @Column(name = "monto_exento", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoExento;

    @Column(name = "total_itbis", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalItbis;

    @Column(name = "monto_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal montoTotal;

    @Column(name = "encf_modificado", length = 13)
    private String encfModificado;

    @Column(name = "origen_tipo", length = 30)
    private String origenTipo;

    @Column(name = "origen_id")
    private Long origenId;

    @Column(name = "es_resumen_consumo", nullable = false)
    private boolean esResumenConsumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private EstadoEcf estado;

    @Column(name = "codigo_seguridad", length = 6)
    private String codigoSeguridad;

    @Column(name = "fecha_hora_firma")
    private LocalDateTime fechaHoraFirma;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "xml_firmado", nullable = false, columnDefinition = "text")
    private String xmlFirmado;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "xml_rfce_firmado", columnDefinition = "text")
    private String xmlRfceFirmado;

    @Column(name = "track_id", length = 64)
    private String trackId;

    @Column(name = "secuencia_utilizada")
    private Boolean secuenciaUtilizada;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mensajes_dgii", columnDefinition = "jsonb")
    private String mensajesDgii;

    @Column(name = "intentos_envio", nullable = false)
    private int intentosEnvio;

    @Column(name = "ultimo_error", length = 1000)
    private String ultimoError;

    @Column(name = "enviado_en")
    private Instant enviadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn = Instant.now();

    @Version
    private long version;

    public TipoEcf tipo() { return TipoEcf.desdeCodigo(tipoEcf); }
}
