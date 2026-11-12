package com.agroinsumos.backend.venta.comun;

import com.agroinsumos.backend.catalogo.cliente.Cliente;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora;
import com.agroinsumos.backend.inventario.articulo.Articulo;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.usuario.Nivel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Política de precios, descuentos e ITBIS de una venta (antes dispersa en FrmFactura, 11.000 líneas).
 * Los totales se calculan con EcfCalculadora: la factura y su e-CF siempre cuadran al centavo.
 */
@Service
@RequiredArgsConstructor
public class PreciosVentaService {

    private static final BigDecimal CIEN = new BigDecimal("100");

    private final ArticuloService articulos;

    /**
     * @param cantidad en la unidad vendida (base o presentación); {@code factor} = unidades base por cada una
     * @param unidad   nombre de la unidad vendida (Libra, Saco, Caja...)
     */
    public record LineaPreparada(Articulo articulo, BigDecimal cantidad, BigDecimal precioUnitario,
                                 BigDecimal descuento, int indicadorItbis, BigDecimal factor, String unidad) {
        /** Cantidad que mueve el inventario (unidades base). */
        public BigDecimal cantidadBase() { return cantidad.multiply(factor); }

        public boolean empaque() { return factor.compareTo(BigDecimal.ONE) != 0; }

        public BigDecimal tasaItbis() {
            return switch (indicadorItbis) {
                case 1 -> EcfCalculadora.TASA_ITBIS1;
                case 2 -> EcfCalculadora.TASA_ITBIS2;
                default -> BigDecimal.ZERO;
            };
        }

        public EcfDtos.Item aItemEcf() {
            // En el comprobante se ve la presentación vendida: "Urea 46% (Saco x 100)"
            String nombre = empaque() && unidad != null
                    ? articulo.getNombre() + " (" + unidad + " x " + factor.stripTrailingZeros().toPlainString() + ")" : articulo.getNombre();
            return new EcfDtos.Item(nombre.length() > 80 ? nombre.substring(0, 80) : nombre, articulo.inventariable() ? 1 : 2,
                    cantidad, precioUnitario, descuento, indicadorItbis);
        }
    }

    /**
     * @param preciosAutorizados true cuando los precios vienen de un documento ya validado (cotización)
     */
    public List<LineaPreparada> preparar(Long companiaId, Cliente cliente, TipoPrecio lista,
                                         List<LineaVentaRequest> lineas, AuthUser usuario, boolean preciosAutorizados) {
        boolean supervisor = usuario.nivel() == Nivel.ADMIN || usuario.nivel() == Nivel.SUPERVISOR || preciosAutorizados;
        return lineas.stream().map(l -> {
            Articulo a = articulos.cargarActiva(companiaId, l.articuloId());
            boolean empaque = Boolean.TRUE.equals(l.empaque());
            if (empaque && !a.tieneEmpaque()) {
                throw new BusinessException("EMPAQUE_NO_DEFINIDO", "'" + a.getNombre() + "' no tiene presentación definida");
            }
            BigDecimal factor = empaque ? a.getFactorEmpaque() : BigDecimal.ONE;
            String unidad = articulos.nombreUnidad(empaque ? a.getUnidadEmpaqueId() : a.getUnidadId());
            BigDecimal precioLista = empaque ? a.precioEmpaque(lista) : a.precio(lista);
            BigDecimal precio = l.precioUnitario() != null ? l.precioUnitario() : precioLista;
            if (precio.compareTo(precioLista) != 0 && !supervisor) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "PRECIO_NO_AUTORIZADO",
                        "Solo un supervisor puede cambiar el precio de '" + a.getNombre() + "'");
            }
            if (precio.signum() <= 0) {
                throw new BusinessException("PRECIO_INVALIDO", "'" + a.getNombre() + "' no tiene precio de venta");
            }
            BigDecimal pct = l.descuentoPorcentaje() == null ? BigDecimal.ZERO : l.descuentoPorcentaje();
            if (pct.compareTo(a.getDescuentoMaximo()) > 0 && !supervisor) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "DESCUENTO_NO_AUTORIZADO", "El descuento máximo de '"
                        + a.getNombre() + "' es " + a.getDescuentoMaximo().stripTrailingZeros().toPlainString() + "%");
            }
            BigDecimal descuento = l.cantidad().multiply(precio).multiply(pct).divide(CIEN, 2, RoundingMode.HALF_UP);
            int indicador = cliente.isExentoItbis() ? 4 : a.getIndicadorItbis();
            return new LineaPreparada(a, l.cantidad(), precio, descuento, indicador, factor, unidad);
        }).toList();
    }

    public static EcfCalculadora.Totales totales(List<LineaPreparada> lineas) {
        return EcfCalculadora.calcular(lineas.stream().map(LineaPreparada::aItemEcf).toList());
    }

    public static BigDecimal itbisLinea(BigDecimal importe, BigDecimal tasa) {
        return importe.multiply(tasa).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
