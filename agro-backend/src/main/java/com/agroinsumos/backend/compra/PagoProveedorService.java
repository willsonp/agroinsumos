package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.caja.CajaMovimiento;
import com.agroinsumos.backend.caja.CajaService;
import com.agroinsumos.backend.catalogo.proveedor.ProveedorService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.common.pago.PagoRequest;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/**
 * Pagos a suplidores y consultas de Cuentas por Pagar (legacy FrmPagoCompra: Agrega_pagoCompra,
 * Actualiza_BalanceCompra, Consulta_PagoCompraPorSuplidor, tmpantiguedadcxp).
 * El efectivo sale de la caja abierta del usuario; cheque/transferencia salen del banco.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PagoProveedorService {

    public static final String DOC_TIPO = "PAGO_PROVEEDOR";

    public record AplicacionRequest(@NotNull Long compraId, @NotNull @DecimalMin("0.01") BigDecimal monto) {}

    public record Request(@NotNull Long proveedorId, @Size(max = 200) String concepto,
                          @NotEmpty List<@Valid AplicacionRequest> aplicaciones, @NotEmpty List<@Valid PagoRequest> formas) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record Response(Long id, long numero, LocalDate fecha, Long proveedorId, Long sesionCajaId, String concepto,
                           BigDecimal total, PagoProveedor.Estado estado, String motivoAnulacion,
                           List<AplicacionRequest> aplicaciones, List<PagoRequest> formas) {
        static Response de(PagoProveedor p) {
            return new Response(p.getId(), p.getNumero(), p.getFecha(), p.getProveedorId(), p.getSesionCajaId(),
                    p.getConcepto(), p.getTotal(), p.getEstado(), p.getMotivoAnulacion(),
                    p.getAplicaciones().stream().map(a -> new AplicacionRequest(a.getCompraId(), a.getMonto())).toList(),
                    p.getFormas().stream().map(f -> new PagoRequest(f.getFormaPago(), f.getMonto(), f.getReferencia(), f.getBanco())).toList());
        }
    }

    public record CompraPendiente(Long id, long numero, String ncfProveedor, LocalDate fecha, LocalDate vencimiento,
                                  BigDecimal aPagar, BigDecimal balance, int diasVencido) {}

    public record Antiguedad(Long proveedorId, String proveedor, BigDecimal corriente, BigDecimal d1a30,
                             BigDecimal d31a60, BigDecimal d61a90, BigDecimal mas90, BigDecimal total) {}

    private final PagoProveedorRepository repo;
    private final CompraRepository compras;
    private final CompraService compraService;
    private final ProveedorService proveedores;
    private final CajaService caja;
    private final NumeradorService numerador;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public Response pagar(AuthUser user, Request r) {
        Long cia = user.companiaId();
        var proveedor = proveedores.cargar(cia, r.proveedorId());
        var p = new PagoProveedor();
        p.setCompaniaId(cia);
        p.setNumero(numerador.siguiente(cia, DOC_TIPO));
        p.setFecha(LocalDate.now(clock));
        p.setProveedorId(proveedor.getId());
        p.setConcepto(r.concepto());
        p.setUsuarioId(user.usuarioId());

        BigDecimal aplicado = BigDecimal.ZERO;
        var vistas = new HashSet<Long>();
        for (var a : r.aplicaciones()) {
            if (!vistas.add(a.compraId())) throw new BusinessException("COMPRA_REPETIDA", "Compra repetida: " + a.compraId());
            Compra c = compraService.cargar(cia, a.compraId());
            if (!c.getProveedorId().equals(proveedor.getId())) throw new BusinessException("COMPRA_OTRO_PROVEEDOR", "La compra " + c.getNumero() + " es de otro suplidor");
            if (c.getEstado() == Compra.Estado.ANULADA) throw new BusinessException("COMPRA_ANULADA", "La compra " + c.getNumero() + " está anulada");
            if (a.monto().compareTo(c.getBalance()) > 0) {
                throw new BusinessException("MONTO_EXCEDE_BALANCE", "El pago a la compra " + c.getNumero() + " excede su balance RD$" + c.getBalance());
            }
            c.setBalance(c.getBalance().subtract(a.monto()));
            if (c.getBalance().signum() == 0) c.setEstado(Compra.Estado.PAGADA);
            var ap = new PagoProveedor.Aplicacion();
            ap.setPago(p);
            ap.setCompraId(c.getId());
            ap.setMonto(a.monto());
            p.getAplicaciones().add(ap);
            aplicado = aplicado.add(a.monto());
        }
        BigDecimal pagado = BigDecimal.ZERO, efectivo = BigDecimal.ZERO;
        for (var f : r.formas()) {
            if (f.forma() == FormaPago.SALDO_A_FAVOR) throw new BusinessException("FORMA_INVALIDA", "Forma de pago no válida para suplidores");
            var fp = new PagoProveedor.Forma();
            fp.setPago(p);
            fp.setFormaPago(f.forma());
            fp.setMonto(f.monto());
            fp.setReferencia(f.referencia());
            fp.setBanco(f.banco());
            p.getFormas().add(fp);
            pagado = pagado.add(f.monto());
            if (f.forma() == FormaPago.EFECTIVO) efectivo = efectivo.add(f.monto());
        }
        if (pagado.compareTo(aplicado) != 0) {
            throw new BusinessException("PAGO_NO_CUADRA", "Las formas de pago (RD$" + pagado + ") deben sumar lo aplicado (RD$" + aplicado + ")");
        }
        p.setTotal(aplicado);
        var sesion = efectivo.signum() > 0 ? caja.abiertaDe(user) : null;
        if (sesion != null) p.setSesionCajaId(sesion.getId());
        repo.save(p);
        if (sesion != null) {
            caja.registrar(sesion, CajaMovimiento.Tipo.PAGO_PROVEEDOR, FormaPago.EFECTIVO, efectivo, DOC_TIPO, p.getId(),
                    "Pago " + p.getNumero() + " a " + proveedor.getNombre());
        }
        return Response.de(p);
    }

    @Transactional
    public Response anular(AuthUser user, Long id, String motivo) {
        Long cia = user.companiaId();
        var p = repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Pago a suplidor", id));
        if (p.getEstado() == PagoProveedor.Estado.ANULADO) throw new BusinessException("PAGO_ANULADO", "El pago ya está anulado");
        for (var a : p.getAplicaciones()) {
            Compra c = compraService.cargar(cia, a.getCompraId());
            c.setBalance(c.getBalance().add(a.getMonto()));
            c.setEstado(Compra.Estado.REGISTRADA);
        }
        BigDecimal efectivo = p.getFormas().stream().filter(f -> f.getFormaPago() == FormaPago.EFECTIVO)
                .map(PagoProveedor.Forma::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (efectivo.signum() > 0) {
            var sesion = caja.sesionPorId(cia, p.getSesionCajaId());
            if (!sesion.abierta()) throw new BusinessException("CAJA_CERRADA", "El pago salió de una caja ya cerrada; registre un ingreso de caja");
            caja.registrar(sesion, CajaMovimiento.Tipo.ANULACION_PAGO, FormaPago.EFECTIVO, efectivo, DOC_TIPO, p.getId(),
                    "Anulación pago " + p.getNumero());
        }
        p.setEstado(PagoProveedor.Estado.ANULADO);
        p.setAnuladoEn(Instant.now());
        p.setMotivoAnulacion(motivo);
        return Response.de(p);
    }

    public Response obtener(Long cia, Long id) {
        return Response.de(repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Pago a suplidor", id)));
    }

    public Page<Response> buscar(Long cia, Long proveedorId, Pageable p) {
        return repo.buscar(cia, proveedorId, p).map(Response::de);
    }

    public List<CompraPendiente> pendientes(Long cia, Long proveedorId) {
        proveedores.cargar(cia, proveedorId);
        LocalDate hoy = LocalDate.now(clock);
        return compras.pendientes(proveedorId).stream().map(c -> new CompraPendiente(c.getId(), c.getNumero(),
                c.getNcfProveedor(), c.getFecha(), c.getFechaVencimiento(), c.aPagar(), c.getBalance(),
                (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(c.getFechaVencimiento(), hoy)))).toList();
    }

    public List<Antiguedad> antiguedad(Long cia) {
        return jdbc.query("""
                with docs as (select proveedor_id, balance, ? - fecha_vencimiento dias from compra
                              where compania_id = ? and balance > 0 and estado <> 'ANULADA')
                select p.id, p.nombre,
                       coalesce(sum(balance) filter (where dias <= 0), 0),
                       coalesce(sum(balance) filter (where dias between 1 and 30), 0),
                       coalesce(sum(balance) filter (where dias between 31 and 60), 0),
                       coalesce(sum(balance) filter (where dias between 61 and 90), 0),
                       coalesce(sum(balance) filter (where dias > 90), 0),
                       sum(balance)
                  from docs join proveedor p on p.id = docs.proveedor_id
                 group by p.id, p.nombre order by 8 desc
                """, (rs, i) -> new Antiguedad(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8)),
                Date.valueOf(LocalDate.now(clock)), cia);
    }
}
