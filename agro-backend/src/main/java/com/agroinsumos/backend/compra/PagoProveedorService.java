package com.agroinsumos.backend.compra;

import com.agroinsumos.backend.banco.MovimientoBancarioService;
import com.agroinsumos.backend.banco.TipoMovimientoBancario;
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

    /** En moneda extranjera: montos en esa moneda; {@code tasaCambio} vacía = la vigente hoy. */
    public record Request(@NotNull Long proveedorId, @Size(max = 200) String concepto,
                          @NotEmpty List<@Valid AplicacionRequest> aplicaciones, @NotEmpty List<@Valid PagoRequest> formas,
                          @Pattern(regexp = "[A-Z]{3}") String moneda, @DecimalMin("0.0001") BigDecimal tasaCambio) {

        public Request(Long proveedorId, String concepto, List<AplicacionRequest> aplicaciones, List<PagoRequest> formas) {
            this(proveedorId, concepto, aplicaciones, formas, null, null);
        }
    }

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record Response(Long id, long numero, LocalDate fecha, Long proveedorId, Long sesionCajaId, String concepto,
                           BigDecimal total, PagoProveedor.Estado estado, String motivoAnulacion,
                           List<AplicacionRequest> aplicaciones, List<PagoRequest> formas, String moneda,
                           BigDecimal tasaCambio, BigDecimal diferenciaCambiaria) {
        static Response de(PagoProveedor p) {
            return new Response(p.getId(), p.getNumero(), p.getFecha(), p.getProveedorId(), p.getSesionCajaId(),
                    p.getConcepto(), p.getTotal(), p.getEstado(), p.getMotivoAnulacion(),
                    p.getAplicaciones().stream().map(a -> new AplicacionRequest(a.getCompraId(), a.getMonto())).toList(),
                    p.getFormas().stream().map(f -> new PagoRequest(f.getFormaPago(), f.getMonto(), f.getReferencia(), f.getBanco(), f.getCuentaBancariaId())).toList(),
                    p.getMoneda(), p.getTasaCambio(), p.getDiferenciaCambiaria());
        }
    }

    public record CompraPendiente(Long id, long numero, String ncfProveedor, LocalDate fecha, LocalDate vencimiento,
                                  BigDecimal aPagar, BigDecimal balance, int diasVencido, String moneda, BigDecimal balanceMoneda) {}

    public record Antiguedad(Long proveedorId, String proveedor, BigDecimal corriente, BigDecimal d1a30,
                             BigDecimal d31a60, BigDecimal d61a90, BigDecimal mas90, BigDecimal total) {}

    private final PagoProveedorRepository repo;
    private final CompraRepository compras;
    private final CompraService compraService;
    private final ProveedorService proveedores;
    private final CajaService caja;
    private final MovimientoBancarioService banco;
    private final com.agroinsumos.backend.moneda.TasaCambioService tasas;
    private final com.agroinsumos.backend.banco.CuentaBancariaRepository cuentasBancarias;
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
        String moneda = r.moneda() == null ? "DOP" : r.moneda();
        boolean ext = !"DOP".equals(moneda);
        BigDecimal tc = tasas.resolver(cia, moneda, p.getFecha(), r.tasaCambio());
        p.setMoneda(moneda);
        p.setTasaCambio(tc);

        BigDecimal aplicado = BigDecimal.ZERO, aplicadoDop = BigDecimal.ZERO;
        var vistas = new HashSet<Long>();
        for (var a : r.aplicaciones()) {
            if (!vistas.add(a.compraId())) throw new BusinessException("COMPRA_REPETIDA", "Compra repetida: " + a.compraId());
            Compra c = compraService.cargar(cia, a.compraId());
            if (!c.getProveedorId().equals(proveedor.getId())) throw new BusinessException("COMPRA_OTRO_PROVEEDOR", "La compra " + c.getNumero() + " es de otro suplidor");
            if (c.getEstado() == Compra.Estado.ANULADA) throw new BusinessException("COMPRA_ANULADA", "La compra " + c.getNumero() + " está anulada");
            if (!c.getMoneda().equals(moneda)) {
                throw new BusinessException("COMPRA_OTRA_MONEDA", "La compra " + c.getNumero() + " es en " + c.getMoneda() + "; el pago es en " + moneda);
            }
            BigDecimal pendiente = ext ? c.getBalanceMoneda() : c.getBalance();
            if (a.monto().compareTo(pendiente) > 0) {
                throw new BusinessException("MONTO_EXCEDE_BALANCE", "El pago a la compra " + c.getNumero() + " excede su balance " + moneda + " " + pendiente);
            }
            // Pesos históricos que rebaja: a la tasa de la compra (si salda la compra, todo lo que quede en pesos)
            BigDecimal montoDop = !ext ? a.monto()
                    : a.monto().compareTo(pendiente) == 0 ? c.getBalance()
                    : a.monto().multiply(c.getTasaCambio()).setScale(2, java.math.RoundingMode.HALF_UP).min(c.getBalance());
            c.setBalance(c.getBalance().subtract(montoDop));
            if (ext) c.setBalanceMoneda(c.getBalanceMoneda().subtract(a.monto()));
            if ((ext ? c.getBalanceMoneda() : c.getBalance()).signum() == 0) c.setEstado(Compra.Estado.PAGADA);
            var ap = new PagoProveedor.Aplicacion();
            ap.setPago(p);
            ap.setCompraId(c.getId());
            ap.setBalanceDespues(ext ? c.getBalanceMoneda() : c.getBalance());
            ap.setMonto(a.monto());
            ap.setMontoDop(montoDop);
            p.getAplicaciones().add(ap);
            aplicado = aplicado.add(a.monto());
            aplicadoDop = aplicadoDop.add(montoDop);
        }
        BigDecimal pagado = BigDecimal.ZERO, efectivo = BigDecimal.ZERO;
        for (var f : r.formas()) {
            if (f.forma() == FormaPago.SALDO_A_FAVOR) throw new BusinessException("FORMA_INVALIDA", "Forma de pago no válida para suplidores");
            if (ext && (f.forma() == FormaPago.EFECTIVO || f.forma() == FormaPago.TARJETA)) {
                throw new BusinessException("FORMA_INVALIDA", "Los pagos en " + moneda + " se hacen por cheque o transferencia");
            }
            var fp = new PagoProveedor.Forma();
            fp.setPago(p);
            fp.setFormaPago(f.forma());
            fp.setMonto(f.monto());
            fp.setReferencia(f.referencia());
            fp.setBanco(f.banco());
            if (f.cuentaBancariaId() != null) {
                if (f.forma() != FormaPago.CHEQUE && f.forma() != FormaPago.TRANSFERENCIA) {
                    throw new BusinessException("CUENTA_NO_APLICA", "Solo cheques y transferencias salen de una cuenta bancaria");
                }
                fp.setCuentaBancariaId(f.cuentaBancariaId());
            }
            p.getFormas().add(fp);
            pagado = pagado.add(f.monto());
            if (f.forma() == FormaPago.EFECTIVO) efectivo = efectivo.add(f.monto());
        }
        if (pagado.compareTo(aplicado) != 0) {
            throw new BusinessException("PAGO_NO_CUADRA", "Las formas de pago (RD$" + pagado + ") deben sumar lo aplicado (RD$" + aplicado + ")");
        }
        p.setTotal(aplicado);
        BigDecimal pagadoDop = p.getFormas().stream().map(f -> f.getMonto().multiply(tc).setScale(2, java.math.RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        p.setDiferenciaCambiaria(pagadoDop.subtract(aplicadoDop));
        var sesion = efectivo.signum() > 0 ? caja.abiertaDe(user) : null;
        if (sesion != null) p.setSesionCajaId(sesion.getId());
        repo.save(p);
        repo.flush();   // el balance del suplidor se lee de la base con los pagos ya aplicados
        p.setBalanceProveedorDespues(balanceProveedor(cia, proveedor.getId()));
        if (sesion != null) {
            caja.registrar(sesion, CajaMovimiento.Tipo.PAGO_PROVEEDOR, FormaPago.EFECTIVO, efectivo, DOC_TIPO, p.getId(),
                    "Pago " + p.getNumero() + " a " + proveedor.getNombre());
        }
        for (var fp : p.getFormas()) {
            if (fp.getCuentaBancariaId() == null) continue;
            var tipo = fp.getFormaPago() == FormaPago.CHEQUE ? TipoMovimientoBancario.CHEQUE : TipoMovimientoBancario.TRANSFERENCIA_SALIDA;
            // La cuenta debe ser de la moneda del pago, o en pesos (se debita el equivalente a la tasa del pago)
            var cuenta = cuentasBancarias.findById(fp.getCuentaBancariaId()).filter(x -> x.getCompaniaId().equals(cia))
                    .orElseThrow(() -> new NotFoundException("Cuenta bancaria", fp.getCuentaBancariaId()));
            BigDecimal montoBanco;
            if (cuenta.getMoneda().equals(moneda)) montoBanco = fp.getMonto();
            else if ("DOP".equals(cuenta.getMoneda())) montoBanco = fp.getMonto().multiply(tc).setScale(2, java.math.RoundingMode.HALF_UP);
            else throw new BusinessException("CUENTA_OTRA_MONEDA", "La cuenta " + cuenta.getNombre() + " es en " + cuenta.getMoneda() + "; el pago es en " + moneda);
            var mov = banco.registrar(user, fp.getCuentaBancariaId(), tipo, p.getFecha(), fp.getReferencia(), proveedor.getNombre(),
                    "Pago " + p.getNumero() + " a " + proveedor.getNombre(), montoBanco, DOC_TIPO, p.getId(),
                    cuenta.getMoneda().equals("DOP") ? BigDecimal.ONE : tc);
            fp.setReferencia(mov.getReferencia());
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
            c.setBalance(c.getBalance().add(a.getMontoDop()));
            if (c.extranjera()) c.setBalanceMoneda(c.getBalanceMoneda().add(a.getMonto()));
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
        banco.anularPorOrigen(user, DOC_TIPO, p.getId(), motivo);
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
                (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(c.getFechaVencimiento(), hoy)), c.getMoneda(),
                c.getBalanceMoneda())).toList();
    }

    /** Lo que se le debe al suplidor en pesos (compras con balance). */
    BigDecimal balanceProveedor(Long cia, Long proveedorId) {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from compra where compania_id = ? and proveedor_id = ? and balance > 0 and estado <> 'ANULADA'",
                BigDecimal.class, cia, proveedorId);
    }

    public record DocumentoPagado(long numero, String ncfProveedor, LocalDate fecha, BigDecimal monto, BigDecimal balanceDespues,
                                  boolean saldado) {}

    /** Comprobante de pago a suplidor impreso: qué compras se pagaron o abonaron y cuánto quedó. */
    public record PagoImpresion(Long id, long numero, LocalDate fecha, String estado, Long proveedorId, String proveedor,
                                String proveedorRnc, String concepto, String atendio, String moneda, BigDecimal tasaCambio,
                                BigDecimal total, BigDecimal balanceProveedor, List<DocumentoPagado> documentos,
                                List<PagoRequest> formas) {}

    public PagoImpresion impresion(Long cia, Long id) {
        var p = repo.findById(id).filter(x -> x.getCompaniaId().equals(cia)).orElseThrow(() -> new NotFoundException("Pago", id));
        var docs = jdbc.query("""
                select c.numero, c.ncf_proveedor, c.fecha, a.monto, a.balance_despues
                  from pago_proveedor_aplicacion a join compra c on c.id = a.compra_id
                 where a.pago_id = ? order by a.id""", (rs, i) -> {
            BigDecimal bal = rs.getBigDecimal("balance_despues");
            return new DocumentoPagado(rs.getLong("numero"), rs.getString("ncf_proveedor"), rs.getObject("fecha", LocalDate.class),
                    rs.getBigDecimal("monto"), bal, bal != null && bal.signum() == 0);
        }, id);
        var datos = jdbc.queryForMap("""
                select pr.nombre, pr.rnc_cedula, u.username
                  from pago_proveedor p join proveedor pr on pr.id = p.proveedor_id left join usuario u on u.id = p.usuario_id
                 where p.id = ?""", id);
        return new PagoImpresion(p.getId(), p.getNumero(), p.getFecha(), p.getEstado().name(), p.getProveedorId(),
                (String) datos.get("nombre"), (String) datos.get("rnc_cedula"), p.getConcepto(), (String) datos.get("username"),
                p.getMoneda(), p.getTasaCambio(), p.getTotal(), p.getBalanceProveedorDespues(), docs,
                p.getFormas().stream().map(f -> new PagoRequest(f.getFormaPago(), f.getMonto(), f.getReferencia(), f.getBanco(),
                        f.getCuentaBancariaId())).toList());
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
