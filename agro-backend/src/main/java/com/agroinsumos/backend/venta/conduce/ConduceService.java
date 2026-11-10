package com.agroinsumos.backend.venta.conduce;

import com.agroinsumos.backend.catalogo.almacen.AlmacenService;
import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.catalogo.unidad.UnidadService;
import com.agroinsumos.backend.catalogo.vendedor.VendedorService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Conduces: la mercancía sale del almacén al entregarse y se factura después (uno o varios conduces del mismo
 * cliente en una factura con e-CF). Los precios quedan fijados en el conduce. Legacy: FrmFactura con facturapro.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConduceService {

    public static final String DOC_TIPO = "CONDUCE";
    private static final BigDecimal CIEN = new BigDecimal("100");

    public record Request(@NotNull Long clienteId, TipoPrecio tipoPrecio, Long almacenId, Long vendedorId,
                          @Size(max = 300) String comentario, @Size(max = 200) String lugarEntrega,
                          @NotEmpty @Size(max = 500) List<@Valid LineaVentaRequest> lineas) {}

    public record FacturarRequest(@NotEmpty @Size(max = 50) List<Long> conduceIds, @NotNull Factura.Condicion condicion,
                                  @Min(1) @Max(365) Integer diasCredito, Integer tipoEcf, @Size(max = 600) String comentario) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record LineaResponse(int linea, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
                                BigDecimal precioUnitario, BigDecimal descuentoPorcentaje) {}

    public record Response(Long id, long numero, LocalDate fecha, Long clienteId, String cliente, Long almacenId,
                           TipoPrecio tipoPrecio, String comentario, String lugarEntrega, BigDecimal totalEstimado,
                           Conduce.Estado estado, Long facturaId, String motivoAnulacion, List<LineaResponse> lineas) {}

    private final ConduceRepository repo;
    private final ClienteService clientes;
    private final AlmacenService almacenes;
    private final VendedorService vendedores;
    private final ArticuloService articulos;
    private final UnidadService unidades;
    private final PreciosVentaService precios;
    private final InventarioService inventario;
    private final FacturaService facturas;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response crear(AuthUser user, Request r) {
        Long cia = user.companiaId();
        var cliente = clientes.cargarActiva(cia, r.clienteId());
        var almacen = almacenes.resolver(cia, r.almacenId());
        TipoPrecio lista = r.tipoPrecio() != null ? r.tipoPrecio() : cliente.getTipoPrecio();
        // Valida precios y descuentos con las mismas reglas de la factura (supervisor para cambiarlos)
        var lineas = precios.preparar(cia, cliente, lista, r.lineas(), user, false);
        var tot = PreciosVentaService.totales(lineas);

        var c = new Conduce();
        c.setCompaniaId(cia);
        c.setNumero(numerador.siguiente(cia, DOC_TIPO));
        c.setFecha(LocalDate.now(clock));
        c.setClienteId(cliente.getId());
        c.setAlmacenId(almacen.getId());
        c.setVendedorId(r.vendedorId() != null ? vendedores.cargarActiva(cia, r.vendedorId()).getId()
                : vendedores.deUsuario(user.usuarioId()).map(v -> v.getId()).orElse(null));
        c.setTipoPrecio(lista);
        c.setComentario(r.comentario());
        c.setLugarEntrega(r.lugarEntrega());
        c.setUsuarioId(user.usuarioId());
        c.setTotalEstimado(tot.montoTotal());
        for (int i = 0; i < lineas.size(); i++) {
            var lp = lineas.get(i);
            var l = new Conduce.Linea();
            l.setConduce(c);
            l.setLinea(i + 1);
            l.setArticuloId(lp.articulo().getId());
            l.setCantidad(lp.cantidad());
            l.setPrecioUnitario(lp.precioUnitario());
            var pct = r.lineas().get(i).descuentoPorcentaje();
            l.setDescuentoPorcentaje(pct == null ? BigDecimal.ZERO : pct);
            l.setCostoUnitario(lp.articulo().getCostoPromedio());
            c.getLineas().add(l);
        }
        repo.save(c);
        for (var lp : lineas) {
            inventario.mover(new InventarioService.Movimiento(lp.articulo(), almacen.getId(), TipoMovimiento.VENTA,
                    lp.cantidad(), lp.articulo().getCostoPromedio(), DOC_TIPO, c.getId()));
        }
        return respuesta(cia, c);
    }

    /** Factura uno o varios conduces abiertos del mismo cliente y almacén (e-CF a los precios de los conduces). */
    @Transactional
    public FacturaDtos.FacturaResponse facturar(AuthUser user, FacturarRequest r) {
        Long cia = user.companiaId();
        var ids = new ArrayList<>(new LinkedHashSet<>(r.conduceIds()));
        var conduces = repo.findByIdInAndCompaniaId(ids, cia);
        if (conduces.size() != ids.size()) throw new NotFoundException("Conduce", ids);
        conduces.sort((a, b) -> Long.compare(a.getNumero(), b.getNumero()));
        var primero = conduces.getFirst();
        List<LineaVentaRequest> lineas = new ArrayList<>();
        for (var c : conduces) {
            if (c.getEstado() != Conduce.Estado.ABIERTO) {
                throw new BusinessException("CONDUCE_NO_ABIERTO", "El conduce " + c.getNumero() + " está " + c.getEstado());
            }
            if (!c.getClienteId().equals(primero.getClienteId()) || !c.getAlmacenId().equals(primero.getAlmacenId())) {
                throw new BusinessException("CONDUCES_DISTINTOS", "Solo se facturan juntos conduces del mismo cliente y almacén");
            }
            c.getLineas().forEach(l -> lineas.add(new LineaVentaRequest(l.getArticuloId(), l.getCantidad(), l.getPrecioUnitario(),
                    l.getDescuentoPorcentaje())));
        }
        String nums = conduces.stream().map(c -> String.valueOf(c.getNumero())).reduce((a, b) -> a + ", " + b).orElse("");
        String comentario = (r.comentario() == null || r.comentario().isBlank() ? "" : r.comentario().trim() + " · ") + "Conduce(s) " + nums;
        var req = new FacturaDtos.CrearFacturaRequest(primero.getClienteId(), r.condicion(), r.diasCredito(), primero.getTipoPrecio(),
                r.tipoEcf(), primero.getAlmacenId(), primero.getVendedorId(), comentario.length() > 600 ? comentario.substring(0, 600) : comentario,
                null, lineas);
        Factura f = facturas.crearEntidad(cia, user, req, true, null, true);
        for (var c : conduces) {
            c.setEstado(Conduce.Estado.FACTURADO);
            c.setFacturaId(f.getId());
        }
        return FacturaDtos.FacturaResponse.de(f);
    }

    /** Solo conduces abiertos: la mercancía regresa al almacén. */
    @Transactional
    public Response anular(AuthUser user, Long id, String motivo) {
        Long cia = user.companiaId();
        var c = cargar(cia, id);
        if (c.getEstado() != Conduce.Estado.ABIERTO) {
            throw new BusinessException("CONDUCE_NO_ABIERTO", "El conduce está " + c.getEstado());
        }
        for (var l : c.getLineas()) {
            inventario.mover(new InventarioService.Movimiento(articulos.cargar(cia, l.getArticuloId()), c.getAlmacenId(),
                    TipoMovimiento.DEVOLUCION_VENTA, l.getCantidad(), l.getCostoUnitario(), DOC_TIPO, c.getId()));
        }
        c.setEstado(Conduce.Estado.ANULADO);
        c.setMotivoAnulacion(motivo);
        return respuesta(cia, c);
    }

    public Response obtener(Long cia, Long id) { return respuesta(cia, cargar(cia, id)); }

    public Page<Response> buscar(Long cia, Long clienteId, Conduce.Estado estado, Pageable p) {
        return repo.buscar(cia, clienteId, estado, p).map(c -> respuesta(cia, c));
    }

    private Conduce cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Conduce", id));
    }

    private Response respuesta(Long cia, Conduce c) {
        var cliente = clientes.cargar(cia, c.getClienteId());
        return new Response(c.getId(), c.getNumero(), c.getFecha(), c.getClienteId(), cliente.getNombre(), c.getAlmacenId(),
                c.getTipoPrecio(), c.getComentario(), c.getLugarEntrega(), c.getTotalEstimado(), c.getEstado(), c.getFacturaId(),
                c.getMotivoAnulacion(),
                c.getLineas().stream().map(l -> {
                    var a = articulos.cargar(cia, l.getArticuloId());
                    return new LineaResponse(l.getLinea(), l.getArticuloId(), a.getNombre(), unidades.cargar(cia, a.getUnidadId()).getNombre(),
                            l.getCantidad(), l.getPrecioUnitario(), l.getDescuentoPorcentaje().setScale(2, RoundingMode.HALF_UP));
                }).toList());
    }
}
