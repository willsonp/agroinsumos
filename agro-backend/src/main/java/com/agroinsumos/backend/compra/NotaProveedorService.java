package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import com.agroinsumos.backend.inventario.kardex.InventarioService;
import com.agroinsumos.backend.inventario.kardex.TipoMovimiento;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/**
 * Notas de crédito / débito de suplidores (CxP). La nota de crédito reduce el balance de la compra
 * (y puede devolver mercancía); la de débito lo aumenta. Ambas se reportan en el 606 con el NCF modificado.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotaProveedorService {

    public static final String DOC_TIPO = "NOTA_PROVEEDOR";

    public record LineaRequest(@NotNull Long compraLineaId, @NotNull @DecimalMin("0.001") BigDecimal cantidad) {}

    /** Sin líneas: subtotal e ITBIS se indican (descuentos, cargos). Con líneas (solo crédito): devolución de mercancía. */
    public record Request(@NotNull NotaProveedor.Tipo tipo, @NotNull Long compraId, LocalDate fecha,
                          @Size(max = 19) String ncf, @NotBlank @Size(max = 200) String concepto,
                          @DecimalMin("0") BigDecimal subtotal, @DecimalMin("0") BigDecimal itbis,
                          @Size(max = 200) List<@Valid LineaRequest> lineas) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record LineaResponse(Long compraLineaId, Long articuloId, BigDecimal cantidad, BigDecimal importe, BigDecimal itbis) {}

    public record Response(Long id, long numero, NotaProveedor.Tipo tipo, Long proveedorId, Long compraId, LocalDate fecha,
                           String ncf, String concepto, BigDecimal subtotal, BigDecimal itbis, BigDecimal total,
                           boolean devuelveInventario, NotaProveedor.Estado estado, String motivoAnulacion,
                           BigDecimal balanceCompra, List<LineaResponse> lineas) {}

    private final NotaProveedorRepository repo;
    private final CompraService compras;
    private final ArticuloService articulos;
    private final InventarioService inventario;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response registrar(AuthUser user, Request r) {
        Long cia = user.companiaId();
        Compra c = compras.cargar(cia, r.compraId());
        if (c.getEstado() == Compra.Estado.ANULADA) throw new BusinessException("COMPRA_ANULADA", "La compra está anulada");
        LocalDate fecha = r.fecha() != null ? r.fecha() : LocalDate.now(clock);
        if (fecha.isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha no puede ser futura");
        if (fecha.isBefore(c.getFecha())) throw new BusinessException("FECHA_INVALIDA", "La nota no puede ser anterior a la compra");
        String ncf = validarNcf(r.ncf(), r.tipo());

        var n = new NotaProveedor();
        n.setCompaniaId(cia);
        n.setTipo(r.tipo());
        n.setProveedorId(c.getProveedorId());
        n.setCompraId(c.getId());
        n.setFecha(fecha);
        n.setNcf(ncf);
        n.setConcepto(r.concepto().trim());
        n.setUsuarioId(user.usuarioId());

        boolean conLineas = r.lineas() != null && !r.lineas().isEmpty();
        if (conLineas) {
            if (r.tipo() != NotaProveedor.Tipo.CREDITO) {
                throw new BusinessException("LINEAS_SOLO_CREDITO", "Solo una nota de crédito devuelve mercancía");
            }
            var vistas = new HashSet<Long>();
            BigDecimal subtotal = BigDecimal.ZERO, itbis = BigDecimal.ZERO;
            for (var lr : r.lineas()) {
                if (!vistas.add(lr.compraLineaId())) throw new BusinessException("LINEA_REPETIDA", "Línea de compra repetida");
                CompraLinea cl = c.getLineas().stream().filter(x -> x.getId().equals(lr.compraLineaId())).findFirst()
                        .orElseThrow(() -> new BusinessException("LINEA_INVALIDA", "La línea " + lr.compraLineaId() + " no es de esta compra"));
                BigDecimal disponible = cl.getCantidad().subtract(repo.cantidadDevuelta(cl.getId()));
                if (lr.cantidad().compareTo(disponible) > 0) {
                    throw new BusinessException("CANTIDAD_EXCEDE", "Solo quedan " + disponible.stripTrailingZeros().toPlainString()
                            + " por devolver de la línea " + cl.getLinea());
                }
                var l = new NotaProveedor.Linea();
                l.setNota(n);
                l.setCompraLineaId(cl.getId());
                l.setArticuloId(cl.getArticuloId());
                l.setCantidad(lr.cantidad());
                l.setImporte(prorratear(cl.getImporte(), lr.cantidad(), cl.getCantidad()));
                l.setItbis(prorratear(cl.getItbis(), lr.cantidad(), cl.getCantidad()));
                l.setCostoUnitario(cl.getCostoFinal());
                n.getLineas().add(l);
                subtotal = subtotal.add(l.getImporte());
                itbis = itbis.add(l.getItbis());
            }
            n.setSubtotal(subtotal);
            n.setItbis(itbis);
            n.setDevuelveInventario(true);
        } else {
            if (r.subtotal() == null) throw new BusinessException("MONTO_REQUERIDO", "Indique el monto de la nota o las líneas devueltas");
            // Montos en la moneda de la compra; se guardan en pesos a la tasa de la compra
            n.setSubtotal(r.subtotal().multiply(c.getTasaCambio()).setScale(2, RoundingMode.HALF_UP));
            n.setItbis(r.itbis() == null ? BigDecimal.ZERO : r.itbis().multiply(c.getTasaCambio()).setScale(2, RoundingMode.HALF_UP));
        }
        n.setTotal(n.getSubtotal().add(n.getItbis()));
        if (n.getTotal().signum() <= 0) throw new BusinessException("MONTO_INVALIDO", "El total de la nota debe ser mayor que cero");

        if (r.tipo() == NotaProveedor.Tipo.CREDITO) {
            if (n.getTotal().compareTo(c.getBalance()) > 0) {
                throw new BusinessException("NOTA_EXCEDE_BALANCE", "La nota de crédito (RD$" + n.getTotal()
                        + ") excede el balance pendiente de la compra (RD$" + c.getBalance() + ")");
            }
            ajustarBalance(c, n.getTotal().negate());
            if (c.getBalance().signum() == 0) c.setEstado(Compra.Estado.PAGADA);
        } else {
            ajustarBalance(c, n.getTotal());
            c.setEstado(Compra.Estado.REGISTRADA);
        }
        n.setNumero(numerador.siguiente(cia, DOC_TIPO));
        try {
            repo.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "NCF_DUPLICADO", "Ya se registró el NCF " + ncf + " de este suplidor");
        }
        for (var l : n.getLineas()) {
            inventario.mover(new InventarioService.Movimiento(articulos.cargar(cia, l.getArticuloId()), c.getAlmacenId(),
                    TipoMovimiento.DEVOLUCION_COMPRA, l.getCantidad(), l.getCostoUnitario(), DOC_TIPO, n.getId()));
        }
        return respuesta(n, c);
    }

    @Transactional
    public Response anular(AuthUser user, Long id, String motivo) {
        Long cia = user.companiaId();
        var n = repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Nota de suplidor", id));
        if (n.getEstado() == NotaProveedor.Estado.ANULADA) throw new BusinessException("NOTA_ANULADA", "La nota ya está anulada");
        Compra c = compras.cargar(cia, n.getCompraId());
        if (n.getTipo() == NotaProveedor.Tipo.CREDITO) {
            ajustarBalance(c, n.getTotal());
            if (c.getEstado() != Compra.Estado.ANULADA) c.setEstado(Compra.Estado.REGISTRADA);
            for (var l : n.getLineas()) {
                inventario.mover(new InventarioService.Movimiento(articulos.cargar(cia, l.getArticuloId()), c.getAlmacenId(),
                        TipoMovimiento.COMPRA, l.getCantidad(), l.getCostoUnitario(), DOC_TIPO, n.getId()));
            }
        } else {
            if (c.getBalance().compareTo(n.getTotal()) < 0) {
                throw new BusinessException("NOTA_PAGADA", "La nota de débito ya fue pagada; anule primero el pago");
            }
            ajustarBalance(c, n.getTotal().negate());
            if (c.getBalance().signum() == 0) c.setEstado(Compra.Estado.PAGADA);
        }
        n.setEstado(NotaProveedor.Estado.ANULADA);
        n.setAnuladoEn(Instant.now());
        n.setMotivoAnulacion(motivo);
        return respuesta(n, c);
    }

    public Response obtener(Long cia, Long id) {
        var n = repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Nota de suplidor", id));
        return respuesta(n, compras.cargar(cia, n.getCompraId()));
    }

    public Page<Response> buscar(Long cia, Long proveedorId, Long compraId, Pageable p) {
        return repo.buscar(cia, proveedorId, compraId, p).map(n -> respuesta(n, compras.cargar(cia, n.getCompraId())));
    }

    /** NC: B04 / E34; ND: B03 / E33. */
    private static String validarNcf(String ncf, NotaProveedor.Tipo tipo) {
        if (ncf == null || ncf.isBlank()) return null;
        String v = ncf.trim().toUpperCase();
        if (!v.matches("B\\d{10}|E\\d{12}")) throw new BusinessException("NCF_INVALIDO", "NCF inválido: " + ncf + " (B + 10 dígitos o E + 12)");
        String serie = v.substring(1, 3);
        boolean ok = tipo == NotaProveedor.Tipo.CREDITO ? serie.equals("04") || serie.equals("34") : serie.equals("03") || serie.equals("33");
        if (!ok) {
            throw new BusinessException("NCF_TIPO_INCORRECTO", tipo == NotaProveedor.Tipo.CREDITO
                    ? "Una nota de crédito usa NCF B04 o e-CF 34" : "Una nota de débito usa NCF B03 o e-CF 33");
        }
        return v;
    }

    /** Ajusta el balance en pesos y, en compras en otra moneda, el balance en esa moneda (a la tasa de la compra). */
    private static void ajustarBalance(Compra c, BigDecimal pesos) {
        c.setBalance(c.getBalance().add(pesos));
        if (c.extranjera()) {
            c.setBalanceMoneda(c.getBalance().signum() == 0 ? BigDecimal.ZERO
                    : c.getBalanceMoneda().add(pesos.divide(c.getTasaCambio(), 2, RoundingMode.HALF_UP)));
        }
    }

    private static BigDecimal prorratear(BigDecimal monto, BigDecimal parte, BigDecimal total) {
        return monto.multiply(parte).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static Response respuesta(NotaProveedor n, Compra c) {
        return new Response(n.getId(), n.getNumero(), n.getTipo(), n.getProveedorId(), n.getCompraId(), n.getFecha(), n.getNcf(),
                n.getConcepto(), n.getSubtotal(), n.getItbis(), n.getTotal(), n.isDevuelveInventario(), n.getEstado(),
                n.getMotivoAnulacion(), c.getBalance(),
                n.getLineas().stream().map(l -> new LineaResponse(l.getCompraLineaId(), l.getArticuloId(), l.getCantidad(),
                        l.getImporte(), l.getItbis())).toList());
    }
}
