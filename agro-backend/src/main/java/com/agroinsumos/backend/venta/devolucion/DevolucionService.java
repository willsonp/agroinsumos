package com.agroinsumos.backend.venta.devolucion;

import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora;
import com.agroinsumos.backend.ecf.emision.EcfEmisionService;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.comun.PreciosVentaService;
import com.agroinsumos.backend.venta.factura.Factura;
import com.agroinsumos.backend.venta.factura.FacturaLinea;
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
import java.util.*;

/**
 * Devoluciones y anulaciones de facturas. Un e-CF aceptado por la DGII no se puede borrar:
 * se anula/corrige con una Nota de Crédito electrónica (34) que referencia el e-NCF original.
 * Legacy: FrmDevolucion / Ctrl_Devolucion (Actauliza_DevFacCredito...).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DevolucionService {

    public static final String DOC_TIPO = "DEVOLUCION";

    public record LineaRequest(@NotNull Long facturaLineaId, @NotNull @DecimalMin("0.001") BigDecimal cantidad,
                               Boolean reingresaInventario) {}

    public record Request(@NotNull Long facturaId, @NotBlank @Size(max = 90) String motivo,
                          @NotEmpty List<@Valid LineaRequest> lineas) {}

    public record LineaResponse(Long facturaLineaId, BigDecimal cantidad, BigDecimal importe, BigDecimal itbis,
                                boolean reingresaInventario) {}

    public record Response(Long id, long numero, LocalDate fecha, Long facturaId, String motivo, int codigoModificacion,
                           BigDecimal totalItbis, BigDecimal total, BigDecimal aplicadoABalance,
                           BigDecimal saldoAFavor, String encf, Long ecfDocumentoId, List<LineaResponse> lineas) {
        static Response de(Devolucion d) {
            return new Response(d.getId(), d.getNumero(), d.getFecha(), d.getFacturaId(), d.getMotivo(),
                    d.getCodigoModificacion(), d.getTotalItbis(), d.getTotal(), d.getAplicadoABalance(),
                    d.getSaldoAFavor(), d.getEncf(), d.getEcfDocumentoId(),
                    d.getLineas().stream().map(l -> new LineaResponse(l.getFacturaLineaId(), l.getCantidad(),
                            l.getImporte(), l.getItbis(), l.isReingresaInventario())).toList());
        }
    }

    private final DevolucionRepository repo;
    private final FacturaService facturas;
    private final ArticuloService articulos;
    private final ClienteService clientes;
    private final InventarioService inventario;
    private final EcfEmisionService ecf;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response devolver(Long companiaId, AuthUser usuario, Request r) {
        Factura f = facturas.cargar(companiaId, r.facturaId());
        if (f.getEstado() == Factura.Estado.ANULADA) {
            throw new BusinessException("FACTURA_ANULADA", "La factura " + f.getNumero() + " ya está anulada");
        }
        if (f.comprobante() == null) {
            throw new BusinessException("FACTURA_SIN_COMPROBANTE", "La factura no tiene NCF/e-NCF que referenciar");
        }
        Map<Long, FacturaLinea> porId = new HashMap<>();
        f.getLineas().forEach(l -> porId.put(l.getId(), l));

        var d = new Devolucion();
        d.setCompaniaId(companiaId);
        d.setNumero(numerador.siguiente(companiaId, DOC_TIPO));
        d.setFecha(LocalDate.now(clock));
        d.setFacturaId(f.getId());
        d.setMotivo(r.motivo().trim());
        d.setUsuarioId(usuario.usuarioId());

        List<EcfDtos.Item> items = new ArrayList<>();
        Set<Long> vistas = new HashSet<>();
        for (var lr : r.lineas()) {
            FacturaLinea fl = porId.get(lr.facturaLineaId());
            if (fl == null) throw new BusinessException("LINEA_INVALIDA", "La línea " + lr.facturaLineaId() + " no pertenece a la factura");
            if (!vistas.add(fl.getId())) throw new BusinessException("LINEA_REPETIDA", "Línea repetida: " + fl.getLinea());
            if (lr.cantidad().compareTo(fl.pendienteDevolver()) > 0) {
                throw new BusinessException("CANTIDAD_EXCEDE", "De '" + fl.getDescripcion() + "' solo quedan "
                        + fl.pendienteDevolver().stripTrailingZeros().toPlainString() + " por devolver");
            }
            // Descuento proporcional a la cantidad devuelta
            BigDecimal desc = fl.getDescuento().multiply(lr.cantidad()).divide(fl.getCantidad(), 2, RoundingMode.HALF_UP);
            var articulo = articulos.cargar(companiaId, fl.getArticuloId());
            items.add(new EcfDtos.Item(fl.getDescripcion(), articulo.inventariable() ? 1 : 2, lr.cantidad(),
                    fl.getPrecioUnitario(), desc, (int) fl.getIndicadorItbis()));

            var dl = new DevolucionLinea();
            dl.setDevolucion(d);
            dl.setFacturaLineaId(fl.getId());
            dl.setCantidad(lr.cantidad());
            dl.setReingresaInventario(lr.reingresaInventario() == null || lr.reingresaInventario());
            d.getLineas().add(dl);
            fl.setCantidadDevuelta(fl.getCantidadDevuelta().add(lr.cantidad()));
        }

        var tot = EcfCalculadora.calcular(items);
        for (int i = 0; i < d.getLineas().size(); i++) {
            var dl = d.getLineas().get(i);
            var fl = porId.get(dl.getFacturaLineaId());
            BigDecimal importe = tot.lineas().get(i).montoItem();
            dl.setImporte(importe);
            dl.setItbis(PreciosVentaService.itbisLinea(importe, fl.getTasaItbis()));
        }
        boolean total = f.getLineas().stream().allMatch(l -> l.pendienteDevolver().signum() == 0);
        d.setCodigoModificacion((short) (total ? 1 : 3));
        d.setTotalItbis(tot.totalItbis());
        d.setTotal(tot.montoTotal()); // mismo cálculo que el e-CF 34: siempre cuadran

        // Aplicación del crédito: primero al balance pendiente de la factura, el resto a favor del cliente
        BigDecimal aplicado = d.getTotal().min(f.getBalance());
        d.setAplicadoABalance(aplicado);
        d.setSaldoAFavor(d.getTotal().subtract(aplicado));
        f.setBalance(f.getBalance().subtract(aplicado));
        if (total) f.setEstado(Factura.Estado.ANULADA);
        else if (f.getBalance().signum() == 0) f.setEstado(Factura.Estado.PAGADA);
        if (d.getSaldoAFavor().signum() > 0) {
            var cliente = clientes.cargar(companiaId, f.getClienteId());
            cliente.setSaldoAFavor(cliente.getSaldoAFavor().add(d.getSaldoAFavor()));
        }
        repo.save(d);

        for (var dl : d.getLineas()) {
            if (!dl.isReingresaInventario()) continue;
            var fl = porId.get(dl.getFacturaLineaId());
            inventario.mover(new InventarioService.Movimiento(articulos.cargar(companiaId, fl.getArticuloId()),
                    f.getAlmacenId(), TipoMovimiento.DEVOLUCION_VENTA, dl.getCantidad(), fl.getCostoUnitario(),
                    DOC_TIPO, d.getId(), null,
                    new com.agroinsumos.backend.inventario.kardex.LoteService.Referencia(FacturaService.DOC_TIPO, f.getId())));
        }

        var comprador = f.getClienteRnc() == null ? null
                : new EcfDtos.Comprador(f.getClienteRnc(), f.getClienteNombre(), null, null);
        boolean credito = f.getCondicion() == Factura.Condicion.CREDITO;
        LocalDate limitePago = credito ? max(f.getFechaVencimiento(), d.getFecha()) : null;
        var nc = ecf.emitir(companiaId, new EcfDtos.EmitirEcfRequest(TipoEcf.NOTA_CREDITO.codigo(), d.getFecha(), "01",
                credito ? 2 : 1, limitePago, comprador, items,
                new EcfDtos.Referencia(f.comprobante(), f.getFecha(), (int) d.getCodigoModificacion(), d.getMotivo()),
                DOC_TIPO, d.getId()));
        d.setEncf(nc.encf());
        d.setEcfDocumentoId(nc.id());
        return Response.de(d);
    }

    private static LocalDate max(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }

    /** Anulación total = devolución de todo lo pendiente con e-CF 34 código 1. */
    @Transactional
    public Response anularFactura(Long companiaId, AuthUser usuario, Long facturaId, String motivo) {
        Factura f = facturas.cargar(companiaId, facturaId);
        var lineas = f.getLineas().stream().filter(l -> l.pendienteDevolver().signum() > 0)
                .map(l -> new LineaRequest(l.getId(), l.pendienteDevolver(), true)).toList();
        if (lineas.isEmpty()) throw new BusinessException("FACTURA_ANULADA", "La factura ya fue devuelta completamente");
        return devolver(companiaId, usuario, new Request(facturaId, motivo, lineas));
    }

    public Response obtener(Long companiaId, Long id) {
        return Response.de(repo.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("Devolución", id)));
    }

    public Page<Response> listar(Long companiaId, Pageable p) {
        return repo.findByCompaniaId(companiaId, p).map(Response::de);
    }

    public List<Response> deFactura(Long companiaId, Long facturaId) {
        facturas.cargar(companiaId, facturaId);
        return repo.findByFacturaIdOrderById(facturaId).stream().map(Response::de).toList();
    }
}
