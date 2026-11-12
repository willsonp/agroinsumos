package com.agroinsumos.backend.venta.apartado;

import com.agroinsumos.backend.banco.CuentaBancariaRepository;
import com.agroinsumos.backend.banco.MovimientoBancarioService;
import com.agroinsumos.backend.banco.TipoMovimientoBancario;
import com.agroinsumos.backend.caja.CajaMovimiento;
import com.agroinsumos.backend.caja.CajaService;
import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.common.pago.PagoRequest;
import com.agroinsumos.backend.cxc.ReciboAplicacion;
import com.agroinsumos.backend.cxc.ReciboService;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Apartados: cada abono entra por la caja (o banco) y queda como anticipo del cliente (saldo a favor). Al completar
 * el monto se factura y se cobra con ese anticipo; al cancelar, el dinero se devuelve o queda como saldo a favor.
 * Legacy: apartado (Agrega_Apartado, Actualiza_Apartado) y facturapro.PagoApartado.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApartadoService {

    public static final String DOC_TIPO = "APARTADO";

    public record Request(@NotNull Long clienteId, @NotBlank @Size(max = 255) String concepto, LocalDate fechaLimite,
                          TipoPrecio tipoPrecio, @NotEmpty @Size(max = 200) List<@Valid LineaVentaRequest> lineas,
                          @Valid PagoRequest abonoInicial) {}

    public record FacturarRequest(Integer tipoEcf) {}

    public record CancelarRequest(@NotBlank @Size(max = 200) String motivo,
                                  /** true: se devuelve el efectivo desde la caja; false: queda como saldo a favor. */
                                  @NotNull Boolean devolverDinero) {}

    public record LineaResponse(int linea, Long articuloId, String descripcion, BigDecimal cantidad, BigDecimal precioUnitario,
                                BigDecimal descuentoPorcentaje, BigDecimal factor) {}

    public record AbonoResponse(Long id, LocalDate fecha, ApartadoAbono.Tipo tipo, FormaPago forma, BigDecimal monto, String referencia) {}

    public record Response(Long id, long numero, LocalDate fecha, Long clienteId, String cliente, String concepto, BigDecimal total,
                           BigDecimal abonado, BigDecimal pendiente, LocalDate fechaLimite, Apartado.Estado estado, Long facturaId,
                           String motivoCancelacion, List<LineaResponse> lineas, List<AbonoResponse> abonos) {}

    private final ApartadoRepository repo;
    private final ApartadoAbonoRepository abonos;
    private final ClienteService clientes;
    private final ArticuloService articulos;
    private final PreciosVentaService precios;
    private final FacturaService facturas;
    private final ReciboService recibos;
    private final CajaService caja;
    private final MovimientoBancarioService banco;
    private final CuentaBancariaRepository cuentasBancarias;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response crear(AuthUser user, Request r) {
        Long cia = user.companiaId();
        var cliente = clientes.cargarActiva(cia, r.clienteId());
        TipoPrecio lista = r.tipoPrecio() != null ? r.tipoPrecio() : cliente.getTipoPrecio();
        var lineas = precios.preparar(cia, cliente, lista, r.lineas(), user, false);
        var a = new Apartado();
        a.setCompaniaId(cia);
        a.setNumero(numerador.siguiente(cia, DOC_TIPO));
        a.setFecha(LocalDate.now(clock));
        a.setClienteId(cliente.getId());
        a.setConcepto(r.concepto().trim());
        a.setFechaLimite(r.fechaLimite());
        a.setUsuarioId(user.usuarioId());
        a.setTotal(PreciosVentaService.totales(lineas).montoTotal());
        for (int i = 0; i < lineas.size(); i++) {
            var lp = lineas.get(i);
            var l = new Apartado.Linea();
            l.setApartado(a);
            l.setLinea(i + 1);
            l.setArticuloId(lp.articulo().getId());
            l.setCantidad(lp.cantidad());
            l.setFactor(lp.factor());
            l.setPrecioUnitario(lp.precioUnitario());
            var pct = r.lineas().get(i).descuentoPorcentaje();
            l.setDescuentoPorcentaje(pct == null ? BigDecimal.ZERO : pct);
            a.getLineas().add(l);
        }
        repo.save(a);
        if (r.abonoInicial() != null) registrarAbono(user, a, r.abonoInicial());
        return respuesta(cia, a);
    }

    @Transactional
    public Response abonar(AuthUser user, Long id, PagoRequest p) {
        var a = cargar(user.companiaId(), id);
        registrarAbono(user, a, p);
        return respuesta(user.companiaId(), a);
    }

    private void registrarAbono(AuthUser user, Apartado a, PagoRequest p) {
        Long cia = user.companiaId();
        if (a.getEstado() != Apartado.Estado.ABIERTO) throw new BusinessException("APARTADO_NO_ABIERTO", "El apartado está " + a.getEstado());
        if (p.forma() == FormaPago.SALDO_A_FAVOR) throw new BusinessException("FORMA_INVALIDA", "Un abono no se paga con saldo a favor");
        if (a.getAbonado().add(p.monto()).compareTo(a.getTotal()) > 0) {
            throw new BusinessException("ABONO_EXCEDE", "El abono excede lo pendiente del apartado: RD$" + a.pendiente());
        }
        var ab = new ApartadoAbono();
        ab.setApartadoId(a.getId());
        ab.setCompaniaId(cia);
        ab.setFecha(LocalDate.now(clock));
        ab.setFormaPago(p.forma());
        ab.setMonto(p.monto());
        ab.setReferencia(p.referencia());
        ab.setUsuarioId(user.usuarioId());
        var sesion = p.forma().pasaPorCaja() ? caja.abiertaDe(user) : null;
        if (sesion != null) ab.setSesionCajaId(sesion.getId());
        abonos.saveAndFlush(ab);
        if (sesion != null) {
            caja.registrar(sesion, CajaMovimiento.Tipo.COBRO, p.forma(), p.monto(), DOC_TIPO, a.getId(), "Abono apartado " + a.getNumero());
        }
        if (p.forma() == FormaPago.TRANSFERENCIA && p.cuentaBancariaId() != null) {
            var cuenta = cuentasBancarias.findById(p.cuentaBancariaId()).filter(c -> c.getCompaniaId().equals(cia))
                    .orElseThrow(() -> new NotFoundException("Cuenta bancaria", p.cuentaBancariaId()));
            if (!"DOP".equals(cuenta.getMoneda())) throw new BusinessException("CUENTA_OTRA_MONEDA", "Los abonos se acreditan a cuentas en pesos");
            banco.registrar(user, cuenta.getId(), TipoMovimientoBancario.TRANSFERENCIA_ENTRADA, ab.getFecha(), p.referencia(), null,
                    "Abono apartado " + a.getNumero(), p.monto(), "ABONO_APARTADO", ab.getId());
        }
        a.setAbonado(a.getAbonado().add(p.monto()));
        var cliente = clientes.cargar(cia, a.getClienteId());
        cliente.setSaldoAFavor(cliente.getSaldoAFavor().add(p.monto()));
    }

    /** Apartado saldado: se factura (e-CF) y se cobra con el anticipo acumulado. */
    @Transactional
    public FacturaDtos.FacturaResponse facturar(AuthUser user, Long id, FacturarRequest r) {
        Long cia = user.companiaId();
        var a = cargar(cia, id);
        if (a.getEstado() != Apartado.Estado.ABIERTO) throw new BusinessException("APARTADO_NO_ABIERTO", "El apartado está " + a.getEstado());
        if (a.pendiente().signum() > 0) {
            throw new BusinessException("APARTADO_PENDIENTE", "Faltan RD$" + a.pendiente() + " por abonar");
        }
        var lineas = a.getLineas().stream().map(l -> new LineaVentaRequest(l.getArticuloId(), l.getCantidad(), l.getPrecioUnitario(),
                l.getDescuentoPorcentaje(), l.getFactor().compareTo(BigDecimal.ONE) != 0)).toList();
        var cliente = clientes.cargar(cia, a.getClienteId());
        var req = new FacturaDtos.CrearFacturaRequest(a.getClienteId(), Factura.Condicion.CONTADO, null, null,
                r == null ? null : r.tipoEcf(), null, null, "Apartado " + a.getNumero() + ": " + a.getConcepto(), null, lineas);
        Factura f = facturas.crearEntidad(cia, user, req, true, null);
        if (f.getTotal().compareTo(a.getAbonado()) > 0 || f.getTotal().compareTo(cliente.getSaldoAFavor()) > 0) {
            throw new BusinessException("ANTICIPO_INSUFICIENTE", "La factura (RD$" + f.getTotal() + ") supera el anticipo disponible");
        }
        recibos.cobrarEntidad(user, new ReciboService.ReciboRequest(a.getClienteId(), "Aplicación de abonos del apartado " + a.getNumero(),
                List.of(new ReciboService.AplicacionRequest(ReciboAplicacion.TipoDocumento.FACTURA, f.getId(), f.getTotal(), null, null)),
                List.of(new PagoRequest(FormaPago.SALDO_A_FAVOR, f.getTotal(), null, null))));
        a.setEstado(Apartado.Estado.FACTURADO);
        a.setFacturaId(f.getId());
        return FacturaDtos.FacturaResponse.de(f);
    }

    /** Cancela: devuelve el efectivo desde la caja del usuario, o lo deja como saldo a favor del cliente. */
    @Transactional
    public Response cancelar(AuthUser user, Long id, CancelarRequest r) {
        Long cia = user.companiaId();
        var a = cargar(cia, id);
        if (a.getEstado() != Apartado.Estado.ABIERTO) throw new BusinessException("APARTADO_NO_ABIERTO", "El apartado está " + a.getEstado());
        if (r.devolverDinero() && a.getAbonado().signum() > 0) {
            var cliente = clientes.cargar(cia, a.getClienteId());
            if (cliente.getSaldoAFavor().compareTo(a.getAbonado()) < 0) {
                throw new BusinessException("SALDO_A_FAVOR_USADO", "El cliente ya usó parte del anticipo; disponible RD$" + cliente.getSaldoAFavor());
            }
            var sesion = caja.abiertaDe(user);
            var dev = new ApartadoAbono();
            dev.setApartadoId(a.getId());
            dev.setCompaniaId(cia);
            dev.setFecha(LocalDate.now(clock));
            dev.setTipo(ApartadoAbono.Tipo.DEVOLUCION);
            dev.setFormaPago(FormaPago.EFECTIVO);
            dev.setMonto(a.getAbonado());
            dev.setSesionCajaId(sesion.getId());
            dev.setUsuarioId(user.usuarioId());
            abonos.save(dev);
            caja.registrar(sesion, CajaMovimiento.Tipo.ANULACION_COBRO, FormaPago.EFECTIVO, a.getAbonado(), DOC_TIPO, a.getId(),
                    "Devolución apartado " + a.getNumero());
            cliente.setSaldoAFavor(cliente.getSaldoAFavor().subtract(a.getAbonado()));
        }
        a.setEstado(Apartado.Estado.CANCELADO);
        a.setMotivoCancelacion(r.motivo().trim() + (r.devolverDinero() ? "" : " (abonos como saldo a favor)"));
        return respuesta(cia, a);
    }

    public Response obtener(Long cia, Long id) { return respuesta(cia, cargar(cia, id)); }

    public Page<Response> buscar(Long cia, Long clienteId, Apartado.Estado estado, Pageable p) {
        return repo.buscar(cia, clienteId, estado, p).map(a -> respuesta(cia, a));
    }

    private Apartado cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Apartado", id));
    }

    private Response respuesta(Long cia, Apartado a) {
        return new Response(a.getId(), a.getNumero(), a.getFecha(), a.getClienteId(), clientes.cargar(cia, a.getClienteId()).getNombre(),
                a.getConcepto(), a.getTotal(), a.getAbonado(), a.pendiente(), a.getFechaLimite(), a.getEstado(), a.getFacturaId(),
                a.getMotivoCancelacion(),
                a.getLineas().stream().map(l -> new LineaResponse(l.getLinea(), l.getArticuloId(), articulos.cargar(cia, l.getArticuloId()).getNombre(),
                        l.getCantidad(), l.getPrecioUnitario(), l.getDescuentoPorcentaje(), l.getFactor())).toList(),
                a.getId() == null ? List.of() : abonos.findByApartadoIdOrderById(a.getId()).stream().map(b -> new AbonoResponse(b.getId(), b.getFecha(),
                        b.getTipo(), b.getFormaPago(), b.getMonto(), b.getReferencia())).toList());
    }
}
