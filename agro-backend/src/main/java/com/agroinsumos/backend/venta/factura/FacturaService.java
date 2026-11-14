package com.agroinsumos.backend.venta.factura;

import com.agroinsumos.backend.catalogo.almacen.AlmacenService;
import com.agroinsumos.backend.catalogo.cliente.Cliente;
import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.catalogo.unidad.UnidadService;
import com.agroinsumos.backend.catalogo.vendedor.VendedorService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.ComprobanteFiscalService;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.comun.PreciosVentaService;
import com.agroinsumos.backend.venta.comun.PreciosVentaService.LineaPreparada;
import com.agroinsumos.backend.venta.factura.FacturaDtos.CrearFacturaRequest;
import com.agroinsumos.backend.venta.factura.FacturaDtos.FacturaResponse;
import com.agroinsumos.backend.venta.factura.FacturaDtos.FacturaResumen;
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

/**
 * Facturación de ventas. En UNA transacción: numeración, precios/ITBIS, salida de inventario,
 * generación y firma del e-CF. Si algo falla (sin existencia, sin secuencia e-NCF, certificado
 * vencido...) no queda nada a medias: ni factura, ni movimiento, ni e-NCF consumido.
 *
 * Legacy: FrmFactura + Ctrl_Factura (Agrega_Factura, Agrega_DetalleFacturaPorAlmacen,
 * Guardar_FacturaCredito, ConsultaNcf_Secuencia).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacturaService {

    public static final String DOC_TIPO = "FACTURA";

    private final FacturaRepository repo;
    private final ClienteService clientes;
    private final AlmacenService almacenes;
    private final VendedorService vendedores;
    private final UnidadService unidades;
    private final PreciosVentaService precios;
    private final InventarioService inventario;
    private final ComprobanteFiscalService ecf;   // e-CF, NCF serie B o sin comprobante, según la empresa
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public FacturaResponse crear(Long companiaId, AuthUser usuario, CrearFacturaRequest r) {
        return FacturaResponse.de(crearEntidad(companiaId, usuario, r, false, null));
    }

    /** También usado al convertir una cotización (precios ya autorizados). */
    @Transactional
    public Factura crearEntidad(Long companiaId, AuthUser usuario, CrearFacturaRequest r,
                                boolean preciosAutorizados, Long cotizacionId) {
        return crearEntidad(companiaId, usuario, r, preciosAutorizados, cotizacionId, false);
    }

    /**
     * @param mercanciaEntregada true al facturar conduces: la mercancía ya salió del inventario con el conduce,
     *                           así que la factura no vuelve a moverlo.
     */
    @Transactional
    public Factura crearEntidad(Long companiaId, AuthUser usuario, CrearFacturaRequest r,
                                boolean preciosAutorizados, Long cotizacionId, boolean mercanciaEntregada) {
        Cliente cliente = clientes.cargarActiva(companiaId, r.clienteId());
        var almacen = almacenes.resolver(companiaId, r.almacenId());
        TipoPrecio lista = r.tipoPrecio() != null ? r.tipoPrecio() : cliente.getTipoPrecio();
        List<LineaPreparada> lineas = precios.preparar(companiaId, cliente, lista, r.lineas(), usuario, preciosAutorizados);
        var tot = PreciosVentaService.totales(lineas);
        LocalDate hoy = LocalDate.now(clock);

        var f = new Factura();
        f.setCompaniaId(companiaId);
        f.setNumero(numerador.siguiente(companiaId, DOC_TIPO));
        f.setFecha(hoy);
        f.setCondicion(r.condicion());
        if (r.condicion() == Factura.Condicion.CREDITO) {
            int dias = r.diasCredito() != null ? r.diasCredito() : cliente.getDiasCredito();
            if (dias <= 0) {
                throw new BusinessException("SIN_CREDITO", "El cliente no tiene días de crédito configurados");
            }
            validarLimiteCredito(cliente, tot.montoTotal());
            f.setDiasCredito(dias);
            f.setFechaVencimiento(hoy.plusDays(dias));
        }
        f.setClienteId(cliente.getId());
        f.setClienteNombre(cliente.getNombre());
        f.setClienteRnc(cliente.getRncCedula());
        f.setVendedorId(r.vendedorId() != null ? vendedores.cargarActiva(companiaId, r.vendedorId()).getId()
                : vendedores.deUsuario(usuario.usuarioId()).map(v -> v.getId()).orElse(null));
        f.setAlmacenId(almacen.getId());
        f.setTipoPrecio(lista);
        f.setComentario(r.comentario());
        f.setNumeroOrden(r.numeroOrden());
        f.setUsuarioId(usuario.usuarioId());
        f.setCotizacionId(cotizacionId);
        f.setDesdeConduce(mercanciaEntregada);

        BigDecimal descuentos = BigDecimal.ZERO;
        BigDecimal costo = BigDecimal.ZERO;
        for (int i = 0; i < lineas.size(); i++) {
            LineaPreparada lp = lineas.get(i);
            var calc = tot.lineas().get(i);
            var l = new FacturaLinea();
            l.setFactura(f);
            l.setLinea(calc.numero());
            l.setArticuloId(lp.articulo().getId());
            l.setDescripcion(lp.articulo().getNombre());
            l.setUnidad(lp.unidad() != null ? lp.unidad() : unidades.cargar(companiaId, lp.articulo().getUnidadId()).getNombre());
            l.setFactor(lp.factor());
            l.setCantidad(lp.cantidad());
            l.setPrecioUnitario(lp.precioUnitario());
            l.setDescuento(calc.descuento());
            l.setIndicadorItbis((short) lp.indicadorItbis());
            l.setTasaItbis(lp.tasaItbis());
            l.setImporte(calc.montoItem());
            l.setItbis(calc.itbis());   // la suma de las líneas cuadra con el ITBIS del documento
            // Costo de la unidad vendida (un saco = 100 × costo de la libra): utilidad = cantidad × (precio − costo)
            l.setCostoUnitario(lp.articulo().getCostoPromedio().multiply(lp.factor()).setScale(4, RoundingMode.HALF_UP));
            f.getLineas().add(l);
            descuentos = descuentos.add(calc.descuento());
            costo = costo.add(lp.cantidadBase().multiply(lp.articulo().getCostoPromedio()));
        }
        f.setMontoGravado(tot.montoGravadoTotal());
        f.setMontoExento(tot.montoExento());
        f.setTotalDescuento(descuentos);
        f.setTotalItbis(tot.totalItbis());
        f.setTotal(tot.montoTotal());
        f.setBalance(tot.montoTotal());
        f.setCostoTotal(costo.setScale(2, RoundingMode.HALF_UP));
        repo.save(f);

        for (LineaPreparada lp : mercanciaEntregada ? List.<LineaPreparada>of() : lineas) {
            inventario.mover(new InventarioService.Movimiento(lp.articulo(), almacen.getId(), TipoMovimiento.VENTA,
                    lp.cantidadBase(), lp.articulo().getCostoPromedio(), DOC_TIPO, f.getId()));
        }

        int tipoEcf = r.tipoEcf() != null ? r.tipoEcf() : cliente.getTipoEcfPreferido();
        var comprador = cliente.getRncCedula() == null ? null
                : new EcfDtos.Comprador(cliente.getRncCedula(), cliente.getNombre(), cliente.getCorreo(), cliente.getDireccion());
        var emitido = ecf.emitir(companiaId, new EcfDtos.EmitirEcfRequest(tipoEcf, hoy, "01",
                f.getCondicion() == Factura.Condicion.CREDITO ? 2 : 1, f.getFechaVencimiento(), comprador,
                lineas.stream().map(LineaPreparada::aItemEcf).toList(), null, DOC_TIPO, f.getId()));
        f.setTipoEcf((short) tipoEcf);
        f.setEncf(emitido.numero());
        f.setEcfDocumentoId(emitido.ecfDocumentoId());
        return f;
    }

    public FacturaResponse obtener(Long companiaId, Long id) {
        return FacturaResponse.de(cargar(companiaId, id));
    }

    public Page<FacturaResumen> buscar(Long companiaId, Long clienteId, Factura.Estado estado, LocalDate desde,
                                       LocalDate hasta, Long numero, Pageable p) {
        return repo.buscar(companiaId, clienteId, estado, desde, hasta, numero, p).map(FacturaResumen::de);
    }

    public Factura cargar(Long companiaId, Long id) {
        return repo.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("Factura", id));
    }

    private void validarLimiteCredito(Cliente c, BigDecimal monto) {
        // Límite 0 = el cliente no tiene crédito aprobado: solo compra de contado
        if (c.getLimiteCredito().signum() <= 0) {
            throw new BusinessException("SIN_CREDITO_APROBADO", "El cliente " + c.getNombre()
                    + " no tiene crédito aprobado (límite de crédito en 0): asígnele un límite en su ficha o venda de contado");
        }
        clientes.bloquear(c.getId());   // la deuda no cambia hasta que esta factura termine
        BigDecimal pendiente = clientes.balancePendiente(c.getId());
        BigDecimal disponible = c.getLimiteCredito().subtract(pendiente).max(BigDecimal.ZERO);
        if (monto.compareTo(disponible) > 0) {
            throw new BusinessException("LIMITE_CREDITO_EXCEDIDO", "Límite de crédito RD$" + c.getLimiteCredito()
                    + ": el cliente debe RD$" + pendiente + " y solo tiene disponible RD$" + disponible
                    + "; esta factura es de RD$" + monto);
        }
    }
}
