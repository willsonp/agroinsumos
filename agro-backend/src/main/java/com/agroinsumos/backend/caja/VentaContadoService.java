package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.pago.PagoRequest;
import com.agroinsumos.backend.cxc.ReciboAplicacion;
import com.agroinsumos.backend.cxc.ReciboService;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.factura.Factura;
import com.agroinsumos.backend.venta.factura.FacturaDtos;
import com.agroinsumos.backend.venta.factura.FacturaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Punto de venta: factura de contado + e-CF + cobro en una sola transacción.
 * Reemplaza el flujo legacy pre-factura (facturapro, estado 'T') -> cobro en FrmCaja.
 * Si el pago no alcanza o la caja está cerrada, no se emite la factura ni se consume e-NCF.
 */
@Service
@RequiredArgsConstructor
public class VentaContadoService {

    public record Request(@NotNull @Valid FacturaDtos.CrearFacturaRequest factura,
                          @NotEmpty List<@Valid PagoRequest> pagos) {}

    public record Response(FacturaDtos.FacturaResponse factura, ReciboService.ReciboResponse recibo) {}

    /** Conduces facturados de contado y cobrados en el momento. */
    public record ConducesRequest(@NotNull @Valid com.agroinsumos.backend.venta.conduce.ConduceService.FacturarRequest facturar,
                                  @NotEmpty List<@Valid PagoRequest> pagos) {}

    private final CajaService caja;
    private final FacturaService facturas;
    private final ReciboService recibos;
    private final com.agroinsumos.backend.venta.conduce.ConduceService conduces;

    @Transactional
    public Response vender(AuthUser user, Request r) {
        if (r.factura().condicion() != Factura.Condicion.CONTADO) {
            throw new BusinessException("CONDICION_INVALIDA", "La venta en caja debe ser de contado; use /ventas/facturas para crédito");
        }
        caja.abiertaDe(user);
        Factura f = facturas.crearEntidad(user.companiaId(), user, r.factura(), false, null);
        return cobrar(user, f, r.pagos());
    }

    /**
     * Factura conduces de contado y los cobra en la caja en la misma transacción (como el punto de venta): si la caja
     * está cerrada o el pago no alcanza, no se emite la factura ni se consume el NCF y los conduces siguen abiertos.
     */
    @Transactional
    public Response facturarConduces(AuthUser user, ConducesRequest r) {
        if (r.facturar().condicion() != Factura.Condicion.CONTADO) {
            throw new BusinessException("CONDICION_INVALIDA", "El cobro en caja es para facturas de contado");
        }
        caja.abiertaDe(user);
        return cobrar(user, conduces.facturarEntidad(user, r.facturar()), r.pagos());
    }

    private Response cobrar(AuthUser user, Factura f, List<PagoRequest> pagos) {
        var recibo = recibos.cobrarEntidad(user, new ReciboService.ReciboRequest(f.getClienteId(), "Factura " + f.getNumero(),
                List.of(new ReciboService.AplicacionRequest(ReciboAplicacion.TipoDocumento.FACTURA, f.getId(), f.getTotal(), null, null)),
                pagos));
        recibo.setOrigen("VENTA");   // el cierre de caja lo cuenta como factura a contado
        return new Response(FacturaDtos.FacturaResponse.de(f), ReciboService.ReciboResponse.de(recibo));
    }
}
