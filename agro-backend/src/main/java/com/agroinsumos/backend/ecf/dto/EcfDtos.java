package com.agroinsumos.backend.ecf.dto;

import com.agroinsumos.backend.ecf.EcfDocumento;
import com.agroinsumos.backend.ecf.EstadoEcf;
import com.agroinsumos.backend.ecf.secuencia.EcfSecuencia;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class EcfDtos {
    private EcfDtos() {}

    // ------------------------------------------------------------ Secuencias
    public record SecuenciaRequest(
            @NotNull Integer tipoEcf,
            @NotNull @Min(1) Long secuenciaDesde,
            @NotNull @Max(9_999_999_999L) Long secuenciaHasta,
            @NotNull @Future LocalDate fechaVencimiento,
            /** 'E' (e-NCF, por defecto) o 'B' (NCF tradicional serie B: hasta 8 dígitos). */
            @Pattern(regexp = "[EB]") String serie) {}

    public record SecuenciaResponse(Long id, int tipoEcf, long secuenciaDesde, long secuenciaHasta, long ultimaUsada,
                                    long disponibles, LocalDate fechaVencimiento, boolean activa, String serie, String prefijo) {
        public static SecuenciaResponse de(EcfSecuencia s) {
            return new SecuenciaResponse(s.getId(), s.getTipoEcf(), s.getSecuenciaDesde(), s.getSecuenciaHasta(),
                    s.getUltimaUsada(), s.disponibles(), s.getFechaVencimiento(), s.isActiva(), s.getSerie(),
                    "B".equals(s.getSerie()) ? "B%02d".formatted(s.getTipoEcf() - 30) : "E" + s.getTipoEcf());
        }
    }

    // ------------------------------------------------------------ Emisión
    /**
     * Solicitud de emisión independiente del módulo de Facturación: el futuro FacturaService
     * construirá esta solicitud desde factura + detalle_factura y llamará a EcfService.emitir().
     */
    public record EmitirEcfRequest(
            @NotNull Integer tipoEcf,
            LocalDate fechaEmision,
            /** 01 Ingresos por operaciones, 02 financieros, 03 extraordinarios, 04 arrendamientos, 05 venta activo, 06 otros. */
            @Pattern(regexp = "0[1-6]") String tipoIngresos,
            /** 1 Contado, 2 Crédito, 3 Gratuito. */
            @NotNull @Min(1) @Max(3) Integer tipoPago,
            LocalDate fechaLimitePago,
            @Valid Comprador comprador,
            @NotEmpty @Size(max = 1000) List<@Valid Item> items,
            @Valid Referencia referencia,
            @Size(max = 30) String origenTipo,
            Long origenId) {}

    public record Comprador(
            @Pattern(regexp = "\\d{9}|\\d{11}", message = "RNC (9) o cédula (11) dígitos") String rnc,
            @Size(max = 150) String razonSocial,
            @Email String correo,
            @Size(max = 100) String direccion) {}

    public record Item(
            @NotBlank @Size(max = 80) String nombre,
            /** 1 Bien, 2 Servicio. */
            @NotNull @Min(1) @Max(2) Integer bienOServicio,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal cantidad,
            @NotNull @DecimalMin("0") BigDecimal precioUnitario,
            @DecimalMin("0") BigDecimal descuento,
            /** 1 ITBIS 18%, 2 ITBIS 16%, 3 ITBIS 0%, 4 Exento. */
            @NotNull @Min(1) @Max(4) Integer indicadorFacturacion,
            /** e-CF 41: ITBIS retenido al vendedor en esta línea. */
            @DecimalMin("0") BigDecimal itbisRetenido,
            /** e-CF 41: ISR retenido al vendedor en esta línea. */
            @DecimalMin("0") BigDecimal isrRetenido) {

        public Item(String nombre, Integer bienOServicio, BigDecimal cantidad, BigDecimal precioUnitario,
                    BigDecimal descuento, Integer indicadorFacturacion) {
            this(nombre, bienOServicio, cantidad, precioUnitario, descuento, indicadorFacturacion, null, null);
        }

        public boolean tieneRetencion() {
            return (itbisRetenido != null && itbisRetenido.signum() > 0) || (isrRetenido != null && isrRetenido.signum() > 0);
        }
    }

    /** Obligatoria para Notas de Débito (33) y Crédito (34). */
    public record Referencia(
            @NotBlank @Pattern(regexp = "E\\d{12}|[A-Z]\\d{10}", message = "e-NCF o NCF modificado inválido") String ncfModificado,
            @NotNull LocalDate fechaNcfModificado,
            /** 1 Anula, 2 Corrige texto, 3 Corrige montos, 4 Reemplazo por contingencia, 5 Referencia factura consumo. */
            @NotNull @Min(1) @Max(5) Integer codigoModificacion,
            @Size(max = 90) String razonModificacion) {}

    public record EcfResponse(Long id, int tipoEcf, String encf, LocalDate fechaEmision, String rncComprador,
                              String razonSocialComprador, BigDecimal montoGravado, BigDecimal montoExento,
                              BigDecimal totalItbis, BigDecimal montoTotal, EstadoEcf estado, String codigoSeguridad,
                              LocalDateTime fechaHoraFirma, boolean resumenConsumo, String trackId,
                              String mensajesDgii, int intentosEnvio, String ultimoError, Instant enviadoEn,
                              String origenTipo, Long origenId) {
        public static EcfResponse de(EcfDocumento d) {
            return new EcfResponse(d.getId(), d.getTipoEcf(), d.getEncf(), d.getFechaEmision(), d.getRncComprador(),
                    d.getRazonSocialComprador(), d.getMontoGravado(), d.getMontoExento(), d.getTotalItbis(),
                    d.getMontoTotal(), d.getEstado(), d.getCodigoSeguridad(), d.getFechaHoraFirma(),
                    d.isEsResumenConsumo(), d.getTrackId(), d.getMensajesDgii(), d.getIntentosEnvio(),
                    d.getUltimoError(), d.getEnviadoEn(), d.getOrigenTipo(), d.getOrigenId());
        }
    }

    public record TimbreResponse(String encf, String codigoSeguridad, String url) {}
}
