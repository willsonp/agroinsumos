package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.catalogo.almacen.AlmacenService;
import com.agroinsumos.backend.catalogo.proveedor.ProveedorService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora;
import com.agroinsumos.backend.ecf.emision.EcfEmisionService;
import com.agroinsumos.backend.inventario.articulo.Articulo;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Registro de facturas de compra: entrada al inventario con costo real (descuento y flete
 * prorrateado), actualización del costo promedio ponderado y cuenta por pagar al suplidor.
 * Legacy: FrmCompra / Ctrl_Compra (deta_compra, facturacreditocxp, Actualiza_BalanceCompra).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompraService {

    public static final String DOC_TIPO = "COMPRA";
    private static final Set<BigDecimal> TASAS = Set.of(new BigDecimal("0"), new BigDecimal("16"), new BigDecimal("18"));
    private static final BigDecimal CIEN = new BigDecimal("100");

    public record LineaRequest(@NotNull Long articuloId, @NotNull @DecimalMin("0.001") BigDecimal cantidad,
                               @NotNull @DecimalMin("0") BigDecimal costoUnitario, @DecimalMin("0") BigDecimal descuento,
                               /** 0, 16 o 18. Por defecto, la tasa del artículo. */
                               BigDecimal tasaItbis) {}

    public record Request(@NotNull Long proveedorId, LocalDate fecha,
                          @Pattern(regexp = "[A-Z]\\d{10}|E\\d{12}", message = "NCF (B0100000001) o e-NCF (E310000000001) inválido")
                          String ncfProveedor,
                          @NotNull Compra.Condicion condicion, @Min(0) @Max(365) Integer diasCredito, Long almacenId,
                          @Size(max = 30) String numeroOrden, @Size(max = 300) String comentario,
                          @DecimalMin("0") BigDecimal flete, @DecimalMin("0") BigDecimal itbisRetenido,
                          @DecimalMin("0") BigDecimal isrRetenido,
                          @NotEmpty @Size(max = 1000) List<@Valid LineaRequest> lineas,
                          /** 606: 01..11 (por defecto 09). */
                          @Pattern(regexp = "0[1-9]|1[01]") String tipoBienServicio,
                          /** 606: 01..08, obligatorio si isrRetenido > 0. */
                          @Pattern(regexp = "0[1-8]") String tipoRetencionIsr,
                          /** true: el suplidor es informal y emitimos nosotros el Comprobante de Compras (e-CF 41). */
                          Boolean emitirComprobanteCompras) {}

    public record LineaResponse(int linea, Long articuloId, BigDecimal cantidad, BigDecimal costoUnitario,
                                BigDecimal descuento, BigDecimal tasaItbis, BigDecimal importe, BigDecimal itbis,
                                BigDecimal costoFinal) {}

    public record Response(Long id, long numero, Long proveedorId, LocalDate fecha, String ncfProveedor,
                           Compra.Condicion condicion, int diasCredito, LocalDate fechaVencimiento, Long almacenId,
                           String numeroOrden, String comentario, BigDecimal subtotal, BigDecimal totalDescuento,
                           BigDecimal totalItbis, BigDecimal flete, BigDecimal total, BigDecimal itbisRetenido,
                           BigDecimal isrRetenido, BigDecimal aPagar, BigDecimal balance, Compra.Estado estado,
                           String tipoBienServicio, String tipoRetencionIsr, String encfCompra, Long ecfDocumentoId,
                           List<LineaResponse> lineas) {
        static Response de(Compra c) {
            return new Response(c.getId(), c.getNumero(), c.getProveedorId(), c.getFecha(), c.getNcfProveedor(),
                    c.getCondicion(), c.getDiasCredito(), c.getFechaVencimiento(), c.getAlmacenId(), c.getNumeroOrden(),
                    c.getComentario(), c.getSubtotal(), c.getTotalDescuento(), c.getTotalItbis(), c.getFlete(), c.getTotal(),
                    c.getItbisRetenido(), c.getIsrRetenido(), c.aPagar(), c.getBalance(), c.getEstado(),
                    c.getTipoBienServicio(), c.getTipoRetencionIsr(), c.getEncfCompra(), c.getEcfDocumentoId(),
                    c.getLineas().stream().map(l -> new LineaResponse(l.getLinea(), l.getArticuloId(), l.getCantidad(),
                            l.getCostoUnitario(), l.getDescuento(), l.getTasaItbis(), l.getImporte(), l.getItbis(),
                            l.getCostoFinal())).toList());
        }
    }

    private final CompraRepository repo;
    private final ProveedorService proveedores;
    private final AlmacenService almacenes;
    private final ArticuloService articulos;
    private final InventarioService inventario;
    private final NumeradorService numerador;
    private final EcfEmisionService ecf;
    private final Clock clock;

    @Transactional
    public Response registrar(AuthUser user, Request r) {
        Long cia = user.companiaId();
        var proveedor = proveedores.cargarActiva(cia, r.proveedorId());
        var almacen = almacenes.resolver(cia, r.almacenId());
        String ncf = r.ncfProveedor() == null || r.ncfProveedor().isBlank() ? null : r.ncfProveedor().trim();
        boolean emite41 = Boolean.TRUE.equals(r.emitirComprobanteCompras());
        if (emite41 && ncf != null) {
            throw new BusinessException("NCF_NO_APLICA", "Si el suplidor entrega NCF no se emite Comprobante de Compras (e-CF 41)");
        }
        if (emite41 && proveedor.getRncCedula() == null) {
            throw new BusinessException("CEDULA_REQUERIDA", "Para emitir el e-CF 41 el suplidor debe tener cédula/RNC registrado");
        }
        if (ncf != null && repo.existsByProveedorIdAndNcfProveedorAndEstadoNot(proveedor.getId(), ncf, Compra.Estado.ANULADA)) {
            throw new BusinessException(HttpStatus.CONFLICT, "NCF_DUPLICADO", "Ya se registró una compra con el NCF " + ncf + " de este suplidor");
        }
        LocalDate fecha = r.fecha() != null ? r.fecha() : LocalDate.now(clock);
        if (fecha.isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha de la compra no puede ser futura");
        int dias = r.condicion() == Compra.Condicion.CREDITO ? (r.diasCredito() == null ? 30 : r.diasCredito()) : 0;

        var c = new Compra();
        c.setCompaniaId(cia);
        c.setNumero(numerador.siguiente(cia, DOC_TIPO));
        c.setProveedorId(proveedor.getId());
        c.setFecha(fecha);
        c.setNcfProveedor(ncf);
        c.setCondicion(r.condicion());
        c.setDiasCredito(dias);
        c.setFechaVencimiento(fecha.plusDays(dias));
        c.setAlmacenId(almacen.getId());
        c.setNumeroOrden(r.numeroOrden());
        c.setComentario(r.comentario());
        c.setUsuarioId(user.usuarioId());
        c.setTipoBienServicio(r.tipoBienServicio() != null ? r.tipoBienServicio() : "09");
        c.setTipoRetencionIsr(r.tipoRetencionIsr());

        BigDecimal subtotal = BigDecimal.ZERO, descuentos = BigDecimal.ZERO, itbis = BigDecimal.ZERO;
        int n = 1;
        for (var lr : r.lineas()) {
            Articulo a = articulos.cargarActiva(cia, lr.articuloId());
            BigDecimal tasa = lr.tasaItbis() != null ? lr.tasaItbis() : a.tasaItbis();
            if (TASAS.stream().noneMatch(t -> t.compareTo(tasa) == 0)) {
                throw new BusinessException("TASA_ITBIS_INVALIDA", "Tasa de ITBIS inválida: " + tasa);
            }
            BigDecimal desc = lr.descuento() == null ? BigDecimal.ZERO : lr.descuento().setScale(2, RoundingMode.HALF_UP);
            BigDecimal importe = lr.cantidad().multiply(lr.costoUnitario()).subtract(desc).setScale(2, RoundingMode.HALF_UP);
            if (importe.signum() < 0) throw new BusinessException("DESCUENTO_INVALIDO", "El descuento supera el importe de '" + a.getNombre() + "'");
            var l = new CompraLinea();
            l.setCompra(c);
            l.setLinea(n++);
            l.setArticuloId(a.getId());
            l.setCantidad(lr.cantidad());
            l.setCostoUnitario(lr.costoUnitario());
            l.setDescuento(desc);
            l.setTasaItbis(tasa);
            l.setImporte(importe);
            l.setItbis(importe.multiply(tasa).divide(CIEN, 2, RoundingMode.HALF_UP));
            c.getLineas().add(l);
            subtotal = subtotal.add(importe);
            descuentos = descuentos.add(desc);
            itbis = itbis.add(l.getItbis());
        }
        BigDecimal flete = r.flete() == null ? BigDecimal.ZERO : r.flete();
        prorratearFlete(c.getLineas(), flete, subtotal);

        c.setSubtotal(subtotal);
        c.setTotalDescuento(descuentos);
        c.setTotalItbis(itbis);
        c.setFlete(flete);
        c.setTotal(subtotal.add(itbis).add(flete));
        c.setItbisRetenido(r.itbisRetenido() == null ? BigDecimal.ZERO : r.itbisRetenido());
        c.setIsrRetenido(r.isrRetenido() == null ? BigDecimal.ZERO : r.isrRetenido());
        if (c.getItbisRetenido().compareTo(itbis) > 0) {
            throw new BusinessException("RETENCION_INVALIDA", "El ITBIS retenido no puede superar el ITBIS facturado");
        }
        if (c.aPagar().signum() < 0) throw new BusinessException("RETENCION_INVALIDA", "Las retenciones superan el total");
        if (c.getIsrRetenido().signum() > 0 && c.getTipoRetencionIsr() == null) {
            throw new BusinessException("TIPO_RETENCION_REQUERIDO", "Indique el tipo de retención de ISR (606: 01..08)");
        }
        List<EcfDtos.Item> items41 = null;
        if (emite41) {
            // El e-CF manda: los totales de la compra se toman del mismo cálculo que el comprobante
            items41 = itemsCompra(cia, c);
            var tot = EcfCalculadora.calcular(items41);
            c.setTotalItbis(tot.totalItbis());
            c.setTotal(tot.montoTotal());
        }
        c.setBalance(c.aPagar());
        if (c.getBalance().signum() == 0) c.setEstado(Compra.Estado.PAGADA);
        repo.save(c);

        for (var l : c.getLineas()) {
            Articulo a = articulos.cargar(cia, l.getArticuloId());
            inventario.mover(new InventarioService.Movimiento(a, almacen.getId(), TipoMovimiento.COMPRA, l.getCantidad(),
                    l.getCostoFinal(), DOC_TIPO, c.getId()));
        }
        if (emite41) {
            boolean credito = c.getCondicion() == Compra.Condicion.CREDITO;
            var emitido = ecf.emitir(cia, new EcfDtos.EmitirEcfRequest(TipoEcf.COMPRAS.codigo(), c.getFecha(), null,
                    credito ? 2 : 1, credito ? c.getFechaVencimiento() : null,
                    new EcfDtos.Comprador(proveedor.getRncCedula(), proveedor.getNombre(), proveedor.getCorreo(), proveedor.getDireccion()),
                    items41, null, DOC_TIPO, c.getId()));
            c.setEncfCompra(emitido.encf());
            c.setEcfDocumentoId(emitido.id());
        }
        return Response.de(c);
    }

    /** Solo si no tiene pagos. Revierte la entrada al inventario (falla si ya se vendió la mercancía). */
    @Transactional
    public Response anular(AuthUser user, Long id) {
        Long cia = user.companiaId();
        Compra c = cargar(cia, id);
        if (c.getEstado() == Compra.Estado.ANULADA) throw new BusinessException("COMPRA_ANULADA", "La compra ya está anulada");
        if (c.getBalance().compareTo(c.aPagar()) != 0) {
            throw new BusinessException("COMPRA_CON_PAGOS", "La compra tiene pagos aplicados; anule los pagos primero");
        }
        for (var l : c.getLineas()) {
            inventario.mover(new InventarioService.Movimiento(articulos.cargar(cia, l.getArticuloId()), c.getAlmacenId(),
                    TipoMovimiento.DEVOLUCION_COMPRA, l.getCantidad(), l.getCostoFinal(), DOC_TIPO, c.getId()));
        }
        c.setBalance(BigDecimal.ZERO);
        c.setEstado(Compra.Estado.ANULADA);
        return Response.de(c);
    }

    public Response obtener(Long cia, Long id) { return Response.de(cargar(cia, id)); }

    public Page<Response> buscar(Long cia, Long proveedorId, Compra.Estado estado, LocalDate desde, LocalDate hasta, Pageable p) {
        return repo.buscar(cia, proveedorId, estado, desde, hasta, p).map(Response::de);
    }

    Compra cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Compra", id));
    }

    /**
     * Ítems del e-CF 41: cada línea con su tasa y las retenciones repartidas proporcionalmente
     * (ITBIS retenido según el ITBIS de la línea; ISR según el importe). El flete va como servicio exento.
     */
    private List<EcfDtos.Item> itemsCompra(Long cia, Compra c) {
        var items = new java.util.ArrayList<EcfDtos.Item>();
        BigDecimal baseIsr = c.getSubtotal().add(c.getFlete());
        BigDecimal retItbisAsignado = BigDecimal.ZERO, retIsrAsignado = BigDecimal.ZERO;
        int n = c.getLineas().size() + (c.getFlete().signum() > 0 ? 1 : 0);
        int i = 0;
        for (var l : c.getLineas()) {
            boolean ultima = ++i == n;
            BigDecimal ri = ultima ? c.getItbisRetenido().subtract(retItbisAsignado)
                    : prorrata(c.getItbisRetenido(), l.getItbis(), c.getTotalItbis());
            BigDecimal rs = ultima ? c.getIsrRetenido().subtract(retIsrAsignado)
                    : prorrata(c.getIsrRetenido(), l.getImporte(), baseIsr);
            retItbisAsignado = retItbisAsignado.add(ri);
            retIsrAsignado = retIsrAsignado.add(rs);
            Articulo a = articulos.cargar(cia, l.getArticuloId());
            int indicador = l.getTasaItbis().compareTo(new BigDecimal("18")) == 0 ? 1
                    : l.getTasaItbis().compareTo(new BigDecimal("16")) == 0 ? 2 : 3;
            items.add(new EcfDtos.Item(a.getNombre(), a.inventariable() ? 1 : 2, l.getCantidad(), l.getCostoUnitario(),
                    l.getDescuento(), indicador, ri, rs));
        }
        if (c.getFlete().signum() > 0) {
            items.add(new EcfDtos.Item("Flete", 2, BigDecimal.ONE, c.getFlete(), null, 4,
                    c.getItbisRetenido().subtract(retItbisAsignado), c.getIsrRetenido().subtract(retIsrAsignado)));
        }
        return items;
    }

    private static BigDecimal prorrata(BigDecimal total, BigDecimal parte, BigDecimal base) {
        if (total.signum() == 0 || base.signum() == 0) return BigDecimal.ZERO;
        return total.multiply(parte).divide(base, 2, RoundingMode.HALF_UP);
    }

    /** Reparte el flete proporcional al importe; la última línea absorbe el redondeo. */
    private static void prorratearFlete(List<CompraLinea> lineas, BigDecimal flete, BigDecimal subtotal) {
        BigDecimal asignado = BigDecimal.ZERO;
        for (int i = 0; i < lineas.size(); i++) {
            var l = lineas.get(i);
            BigDecimal parte;
            if (flete.signum() == 0) parte = BigDecimal.ZERO;
            else if (i == lineas.size() - 1) parte = flete.subtract(asignado);
            else if (subtotal.signum() == 0) parte = BigDecimal.ZERO;
            else parte = flete.multiply(l.getImporte()).divide(subtotal, 2, RoundingMode.HALF_UP);
            asignado = asignado.add(parte);
            l.setCostoFinal(l.getImporte().add(parte).divide(l.getCantidad(), 4, RoundingMode.HALF_UP));
        }
    }
}
