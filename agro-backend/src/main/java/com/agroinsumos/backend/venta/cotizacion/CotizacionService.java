package com.agroinsumos.backend.venta.cotizacion;

import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.comun.LineaVentaRequest;
import com.agroinsumos.backend.venta.comun.PreciosVentaService;
import com.agroinsumos.backend.venta.factura.Factura;
import com.agroinsumos.backend.venta.factura.FacturaDtos;
import com.agroinsumos.backend.venta.factura.FacturaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Cotizaciones (legacy agrega_cotizacion / Agrega_DetalleCotizacion). No mueven inventario ni emiten e-CF. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CotizacionService {

    public record Request(@NotNull Long clienteId, TipoPrecio tipoPrecio, Long vendedorId,
                          @Min(1) @Max(90) Integer diasValidez, @Size(max = 600) String comentario,
                          @NotEmpty @Size(max = 1000) List<@Valid LineaVentaRequest> lineas) {}

    public record FacturarRequest(@NotNull Factura.Condicion condicion, @Min(1) @Max(365) Integer diasCredito,
                                  Integer tipoEcf, Long almacenId) {}

    public record LineaResponse(int linea, Long articuloId, BigDecimal cantidad, BigDecimal precioUnitario,
                                BigDecimal descuento, BigDecimal importe, BigDecimal itbis) {}

    public record Response(Long id, long numero, LocalDate fecha, LocalDate validaHasta, Long clienteId,
                           Long vendedorId, TipoPrecio tipoPrecio, String comentario, BigDecimal totalItbis,
                           BigDecimal total, Cotizacion.Estado estado, Long facturaId, List<LineaResponse> lineas) {
        static Response de(Cotizacion c) {
            return new Response(c.getId(), c.getNumero(), c.getFecha(), c.getValidaHasta(), c.getClienteId(),
                    c.getVendedorId(), c.getTipoPrecio(), c.getComentario(), c.getTotalItbis(), c.getTotal(),
                    c.getEstado(), c.getFacturaId(), c.getLineas().stream().map(l -> new LineaResponse(l.getLinea(),
                    l.getArticuloId(), l.getCantidad(), l.getPrecioUnitario(), l.getDescuento(), l.getImporte(),
                    l.getItbis())).toList());
        }
    }

    private final CotizacionRepository repo;
    private final ClienteService clientes;
    private final PreciosVentaService precios;
    private final FacturaService facturas;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response crear(Long companiaId, AuthUser usuario, Request r) {
        var cliente = clientes.cargarActiva(companiaId, r.clienteId());
        TipoPrecio lista = r.tipoPrecio() != null ? r.tipoPrecio() : cliente.getTipoPrecio();
        var lineas = precios.preparar(companiaId, cliente, lista, r.lineas(), usuario, false);
        var tot = PreciosVentaService.totales(lineas);
        var c = new Cotizacion();
        c.setCompaniaId(companiaId);
        c.setNumero(numerador.siguiente(companiaId, "COTIZACION"));
        c.setFecha(LocalDate.now(clock));
        c.setValidaHasta(c.getFecha().plusDays(r.diasValidez() == null ? 15 : r.diasValidez()));
        c.setClienteId(cliente.getId());
        c.setVendedorId(r.vendedorId());
        c.setTipoPrecio(lista);
        c.setComentario(r.comentario());
        for (int i = 0; i < lineas.size(); i++) {
            var lp = lineas.get(i);
            var calc = tot.lineas().get(i);
            var l = new CotizacionLinea();
            l.setCotizacion(c);
            l.setLinea(calc.numero());
            l.setArticuloId(lp.articulo().getId());
            l.setCantidad(lp.cantidad());
            l.setPrecioUnitario(lp.precioUnitario());
            l.setDescuento(calc.descuento());
            l.setImporte(calc.montoItem());
            l.setItbis(PreciosVentaService.itbisLinea(calc.montoItem(), lp.tasaItbis()));
            c.getLineas().add(l);
        }
        c.setTotalItbis(tot.totalItbis());
        c.setTotal(tot.montoTotal());
        return Response.de(repo.save(c));
    }

    /** Convierte la cotización en factura con los precios cotizados (si sigue vigente). */
    @Transactional
    public FacturaDtos.FacturaResponse facturar(Long companiaId, AuthUser usuario, Long id, FacturarRequest r) {
        var c = cargar(companiaId, id);
        if (c.getEstado() != Cotizacion.Estado.ABIERTA) {
            throw new BusinessException("COTIZACION_NO_ABIERTA", "La cotización está " + c.getEstado());
        }
        if (c.getValidaHasta() != null && c.getValidaHasta().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("COTIZACION_VENCIDA", "La cotización venció el " + c.getValidaHasta());
        }
        var lineas = c.getLineas().stream().map(l -> new LineaVentaRequest(l.getArticuloId(), l.getCantidad(),
                l.getPrecioUnitario(), porcentaje(l))).toList();
        var req = new FacturaDtos.CrearFacturaRequest(c.getClienteId(), r.condicion(), r.diasCredito(), c.getTipoPrecio(),
                r.tipoEcf(), r.almacenId(), c.getVendedorId(), c.getComentario(), null, lineas);
        Factura f = facturas.crearEntidad(companiaId, usuario, req, true, c.getId());
        c.setEstado(Cotizacion.Estado.FACTURADA);
        c.setFacturaId(f.getId());
        return FacturaDtos.FacturaResponse.de(f);
    }

    @Transactional
    public void anular(Long companiaId, Long id) {
        var c = cargar(companiaId, id);
        if (c.getEstado() != Cotizacion.Estado.ABIERTA) {
            throw new BusinessException("COTIZACION_NO_ABIERTA", "La cotización está " + c.getEstado());
        }
        c.setEstado(Cotizacion.Estado.ANULADA);
    }

    public Response obtener(Long companiaId, Long id) { return Response.de(cargar(companiaId, id)); }

    public Page<Response> buscar(Long companiaId, Long clienteId, Cotizacion.Estado estado, Pageable p) {
        return repo.buscar(companiaId, clienteId, estado, p).map(Response::de);
    }

    private Cotizacion cargar(Long companiaId, Long id) {
        return repo.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("Cotización", id));
    }

    private static BigDecimal porcentaje(CotizacionLinea l) {
        BigDecimal bruto = l.getCantidad().multiply(l.getPrecioUnitario());
        return bruto.signum() == 0 ? BigDecimal.ZERO
                : l.getDescuento().multiply(new BigDecimal("100")).divide(bruto, 6, RoundingMode.HALF_UP);
    }
}
