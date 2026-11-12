package com.agroinsumos.backend.venta.factura;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.venta.comun.LineaVentaRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FacturaDtos {
    private FacturaDtos() {}

    public record CrearFacturaRequest(
            @NotNull Long clienteId,
            @NotNull Factura.Condicion condicion,
            /** Por defecto: días de crédito del cliente. */
            @Min(1) @Max(365) Integer diasCredito,
            /** Por defecto: lista del cliente (CONTADO/CREDITO/MAYOR). */
            TipoPrecio tipoPrecio,
            /** Por defecto: tipo e-CF preferido del cliente (31, 32, 44, 45). */
            Integer tipoEcf,
            /** Por defecto: almacén principal. */
            Long almacenId,
            /** Por defecto: vendedor asociado al usuario. */
            Long vendedorId,
            @Size(max = 600) String comentario,
            @Size(max = 30) String numeroOrden,
            @NotEmpty @Size(max = 1000) List<@Valid LineaVentaRequest> lineas) {}

    public record LineaResponse(Long id, int linea, Long articuloId, String descripcion, String unidad,
                                BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal descuento,
                                int indicadorItbis, BigDecimal tasaItbis, BigDecimal importe, BigDecimal itbis,
                                BigDecimal cantidadDevuelta, BigDecimal factor) {}

    public record FacturaResponse(Long id, long numero, LocalDate fecha, Factura.Condicion condicion, int diasCredito,
                                  LocalDate fechaVencimiento, Long clienteId, String clienteNombre, String clienteRnc,
                                  Long vendedorId, Long almacenId, TipoPrecio tipoPrecio, String comentario,
                                  String numeroOrden, BigDecimal montoGravado, BigDecimal montoExento,
                                  BigDecimal totalDescuento, BigDecimal totalItbis, BigDecimal total,
                                  BigDecimal balance, Integer tipoEcf, String encf, Long ecfDocumentoId,
                                  String ncfLegacy, Factura.Estado estado, Instant creadoEn,
                                  List<LineaResponse> lineas) {

        public static FacturaResponse de(Factura f) {
            return new FacturaResponse(f.getId(), f.getNumero(), f.getFecha(), f.getCondicion(), f.getDiasCredito(),
                    f.getFechaVencimiento(), f.getClienteId(), f.getClienteNombre(), f.getClienteRnc(),
                    f.getVendedorId(), f.getAlmacenId(), f.getTipoPrecio(), f.getComentario(), f.getNumeroOrden(),
                    f.getMontoGravado(), f.getMontoExento(), f.getTotalDescuento(), f.getTotalItbis(), f.getTotal(),
                    f.getBalance(), f.getTipoEcf() == null ? null : f.getTipoEcf().intValue(), f.getEncf(),
                    f.getEcfDocumentoId(), f.getNcfLegacy(), f.getEstado(), f.getCreadoEn(),
                    f.getLineas().stream().map(l -> new LineaResponse(l.getId(), l.getLinea(), l.getArticuloId(),
                            l.getDescripcion(), l.getUnidad(), l.getCantidad(), l.getPrecioUnitario(), l.getDescuento(),
                            l.getIndicadorItbis(), l.getTasaItbis(), l.getImporte(), l.getItbis(),
                            l.getCantidadDevuelta(), l.getFactor())).toList());
        }
    }

    /** Resumen para listados (sin líneas). */
    public record FacturaResumen(Long id, long numero, LocalDate fecha, Factura.Condicion condicion, String clienteNombre,
                                 BigDecimal total, BigDecimal balance, String encf, Factura.Estado estado) {
        public static FacturaResumen de(Factura f) {
            return new FacturaResumen(f.getId(), f.getNumero(), f.getFecha(), f.getCondicion(), f.getClienteNombre(),
                    f.getTotal(), f.getBalance(), f.getEncf(), f.getEstado());
        }
    }

    public record AnularRequest(@NotBlank @Size(max = 90) String motivo) {}
}
