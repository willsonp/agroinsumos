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

    public record LineaPreparada(Articulo articulo, BigDecimal cantidad, BigDecimal precioUnitario,
                                 BigDecimal descuento, int indicadorItbis) {
        public BigDecimal tasaItbis() {
            return switch (indicadorItbis) {
                case 1 -> EcfCalculadora.TASA_ITBIS1;
                case 2 -> EcfCalculadora.TASA_ITBIS2;
                default -> BigDecimal.ZERO;
            };
        }

        public EcfDtos.Item aItemEcf() {
            return new EcfDtos.Item(articulo.getNombre(), articulo.inventariable() ? 1 : 2, cantidad, precioUnitario,
                    descuento, indicadorItbis);
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
            BigDecimal precioLista = a.precio(lista);
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
            return new LineaPreparada(a, l.cantidad(), precio, descuento, indicador);
        }).toList();
    }

    public static EcfCalculadora.Totales totales(List<LineaPreparada> lineas) {
        return EcfCalculadora.calcular(lineas.stream().map(LineaPreparada::aItemEcf).toList());
    }

    public static BigDecimal itbisLinea(BigDecimal importe, BigDecimal tasa) {
        return importe.multiply(tasa).divide(CIEN, 2, RoundingMode.HALF_UP);
    }
}
