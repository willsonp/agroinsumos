package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.banco.MovimientoBancarioService;
import com.agroinsumos.backend.banco.TipoMovimientoBancario;
import com.agroinsumos.backend.caja.CajaMovimiento;
import com.agroinsumos.backend.caja.CajaService;
import com.agroinsumos.backend.catalogo.cliente.Cliente;
import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.common.pago.PagoRequest;
import com.agroinsumos.backend.cxc.ReciboAplicacion.TipoDocumento;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.factura.Factura;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/**
 * Recibos de ingreso: cobro de facturas (contado pendiente en caja o crédito) y notas de débito.
 * Legacy: FrmReciboIngreso / Ctrl_Recibo (Agrega_Recibo, Agrega_DetalleRecibo,
 * Actualiza_BalanceFactura..., ActualizaMontoInicial_FacturaEliminarRecibo).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReciboService {

    public static final String DOC_TIPO = "RECIBO";

    public record AplicacionRequest(@NotNull TipoDocumento documentoTipo, @NotNull Long documentoId,
                                    @NotNull @DecimalMin("0") BigDecimal monto,
                                    @DecimalMin("0") BigDecimal retencionItbis, @DecimalMin("0") BigDecimal retencionIsr) {}

    public record ReciboRequest(@NotNull Long clienteId, @Size(max = 200) String concepto,
                                @NotEmpty @Size(max = 200) List<@Valid AplicacionRequest> aplicaciones,
                                @Size(max = 10) List<@Valid PagoRequest> pagos) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record AplicacionResponse(TipoDocumento documentoTipo, Long documentoId, BigDecimal monto,
                                     BigDecimal retencionItbis, BigDecimal retencionIsr) {}

    public record PagoResponse(FormaPago forma, BigDecimal monto, String referencia, String banco, Long cuentaBancariaId) {}

    public record ReciboResponse(Long id, long numero, LocalDate fecha, Long clienteId, Long sesionCajaId, String concepto,
                                 BigDecimal totalAplicado, BigDecimal totalRetenciones, BigDecimal totalRecibido,
                                 BigDecimal devuelta, Recibo.Estado estado, String motivoAnulacion,
                                 List<AplicacionResponse> aplicaciones, List<PagoResponse> pagos) {
        public static ReciboResponse de(Recibo r) {
            return new ReciboResponse(r.getId(), r.getNumero(), r.getFecha(), r.getClienteId(), r.getSesionCajaId(),
                    r.getConcepto(), r.getTotalAplicado(), r.getTotalRetenciones(), r.getTotalRecibido(), r.getDevuelta(),
                    r.getEstado(), r.getMotivoAnulacion(),
                    r.getAplicaciones().stream().map(a -> new AplicacionResponse(a.getDocumentoTipo(), a.getDocumentoId(),
                            a.getMonto(), a.getRetencionItbis(), a.getRetencionIsr())).toList(),
                    r.getPagos().stream().map(p -> new PagoResponse(p.getFormaPago(), p.getMonto(), p.getReferencia(),
                            p.getBanco(), p.getCuentaBancariaId())).toList());
        }
    }

    private final ReciboRepository repo;
    private final NotaDebitoRepository notasDebito;
    private final FacturaService facturas;
    private final ClienteService clientes;
    private final CajaService caja;
    private final MovimientoBancarioService banco;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public ReciboResponse cobrar(AuthUser user, ReciboRequest r) {
        return ReciboResponse.de(cobrarEntidad(user, r));
    }

    @Transactional
    public Recibo cobrarEntidad(AuthUser user, ReciboRequest r) {
        Long cia = user.companiaId();
        Cliente cliente = clientes.cargar(cia, r.clienteId());
        List<PagoRequest> pagos = r.pagos() == null ? List.of() : r.pagos();

        var rec = new Recibo();
        rec.setCompaniaId(cia);
        rec.setNumero(numerador.siguiente(cia, DOC_TIPO));
        rec.setFecha(LocalDate.now(clock));
        rec.setClienteId(cliente.getId());
        rec.setConcepto(r.concepto());
        rec.setUsuarioId(user.usuarioId());

        BigDecimal aplicado = BigDecimal.ZERO, retenciones = BigDecimal.ZERO;
        var vistos = new HashSet<String>();
        for (var a : r.aplicaciones()) {
            if (!vistos.add(a.documentoTipo() + ":" + a.documentoId())) {
                throw new BusinessException("DOCUMENTO_REPETIDO", "Documento repetido en el recibo: " + a.documentoId());
            }
            var ap = new ReciboAplicacion();
            ap.setRecibo(rec);
            ap.setDocumentoTipo(a.documentoTipo());
            ap.setDocumentoId(a.documentoId());
            ap.setMonto(a.monto());
            ap.setRetencionItbis(cero(a.retencionItbis()));
            ap.setRetencionIsr(cero(a.retencionIsr()));
            if (ap.totalAbonado().signum() <= 0) throw new BusinessException("MONTO_INVALIDO", "Abono en cero para el documento " + a.documentoId());
            abonar(cia, cliente.getId(), ap.getDocumentoTipo(), ap.getDocumentoId(), ap.totalAbonado());
            rec.getAplicaciones().add(ap);
            aplicado = aplicado.add(ap.getMonto());
            retenciones = retenciones.add(ap.getRetencionItbis()).add(ap.getRetencionIsr());
        }

        BigDecimal recibido = BigDecimal.ZERO, efectivo = BigDecimal.ZERO, saldoFavor = BigDecimal.ZERO;
        boolean usaCaja = false;
        for (var p : pagos) {
            var rp = new ReciboPago();
            rp.setRecibo(rec);
            rp.setFormaPago(p.forma());
            rp.setMonto(p.monto());
            rp.setReferencia(p.referencia());
            rp.setBanco(p.banco());
            if (p.cuentaBancariaId() != null) {
                if (p.forma() != FormaPago.TRANSFERENCIA) {
                    throw new BusinessException("CUENTA_NO_APLICA", "Solo las transferencias se acreditan directo a una cuenta bancaria; los cheques se depositan desde la caja");
                }
                rp.setCuentaBancariaId(p.cuentaBancariaId());
            }
            rec.getPagos().add(rp);
            recibido = recibido.add(p.monto());
            if (p.forma() == FormaPago.EFECTIVO) efectivo = efectivo.add(p.monto());
            if (p.forma() == FormaPago.SALDO_A_FAVOR) saldoFavor = saldoFavor.add(p.monto());
            usaCaja |= p.forma().pasaPorCaja();
        }
        if (recibido.compareTo(aplicado) < 0) {
            throw new BusinessException("PAGO_INSUFICIENTE", "Pagos RD$" + recibido + " no cubren lo aplicado RD$" + aplicado);
        }
        BigDecimal devuelta = recibido.subtract(aplicado);
        if (devuelta.compareTo(efectivo) > 0) {
            throw new BusinessException("EXCESO_NO_EFECTIVO", "Solo se puede dar devuelta sobre pagos en efectivo");
        }
        if (saldoFavor.compareTo(cliente.getSaldoAFavor()) > 0) {
            throw new BusinessException("SALDO_A_FAVOR_INSUFICIENTE", "Saldo a favor disponible: RD$" + cliente.getSaldoAFavor());
        }
        cliente.setSaldoAFavor(cliente.getSaldoAFavor().subtract(saldoFavor));
        rec.setTotalAplicado(aplicado);
        rec.setTotalRetenciones(retenciones);
        rec.setTotalRecibido(recibido);
        rec.setDevuelta(devuelta);

        var sesion = usaCaja ? caja.abiertaDe(user) : null;
        if (sesion != null) rec.setSesionCajaId(sesion.getId());
        repo.save(rec);
        if (sesion != null) {
            for (var p : rec.getPagos()) {
                BigDecimal neto = p.getFormaPago() == FormaPago.EFECTIVO ? null : p.getMonto();
                if (neto != null) caja.registrar(sesion, CajaMovimiento.Tipo.COBRO, p.getFormaPago(), neto, DOC_TIPO, rec.getId(),
                        "Recibo " + rec.getNumero());
            }
            // El efectivo entra neto de la devuelta
            caja.registrar(sesion, CajaMovimiento.Tipo.COBRO, FormaPago.EFECTIVO, efectivo.subtract(devuelta), DOC_TIPO,
                    rec.getId(), "Recibo " + rec.getNumero());
        }
        for (var p : rec.getPagos()) {
            if (p.getCuentaBancariaId() == null) continue;
            var mov = banco.registrar(user, p.getCuentaBancariaId(), TipoMovimientoBancario.TRANSFERENCIA_ENTRADA, rec.getFecha(),
                    p.getReferencia(), cliente.getNombre(), "Recibo " + rec.getNumero() + " de " + cliente.getNombre(), p.getMonto(),
                    DOC_TIPO, rec.getId());
            p.setReferencia(mov.getReferencia());
        }
        return rec;
    }

    /** Solo supervisor. Si hubo dinero en caja, la sesión original debe seguir abierta. */
    @Transactional
    public ReciboResponse anular(AuthUser user, Long id, String motivo) {
        Long cia = user.companiaId();
        Recibo rec = repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Recibo", id));
        if (rec.getEstado() == Recibo.Estado.ANULADO) throw new BusinessException("RECIBO_ANULADO", "El recibo ya está anulado");
        for (var a : rec.getAplicaciones()) revertir(cia, a.getDocumentoTipo(), a.getDocumentoId(), a.totalAbonado());
        BigDecimal saldoFavor = rec.getPagos().stream().filter(p -> p.getFormaPago() == FormaPago.SALDO_A_FAVOR)
                .map(ReciboPago::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (saldoFavor.signum() > 0) {
            var cliente = clientes.cargar(cia, rec.getClienteId());
            cliente.setSaldoAFavor(cliente.getSaldoAFavor().add(saldoFavor));
        }
        if (rec.getSesionCajaId() != null) {
            var sesion = caja.sesionPorId(cia, rec.getSesionCajaId());
            if (!sesion.abierta()) {
                throw new BusinessException("CAJA_CERRADA", "El recibo se cobró en una caja ya cerrada; registre un egreso o nota de crédito");
            }
            BigDecimal efectivo = BigDecimal.ZERO;
            for (var p : rec.getPagos()) {
                if (p.getFormaPago() == FormaPago.EFECTIVO) efectivo = efectivo.add(p.getMonto());
                else caja.registrar(sesion, CajaMovimiento.Tipo.ANULACION_COBRO, p.getFormaPago(), p.getMonto(), DOC_TIPO,
                        rec.getId(), "Anulación recibo " + rec.getNumero());
            }
            caja.registrar(sesion, CajaMovimiento.Tipo.ANULACION_COBRO, FormaPago.EFECTIVO, efectivo.subtract(rec.getDevuelta()),
                    DOC_TIPO, rec.getId(), "Anulación recibo " + rec.getNumero());
        }
        banco.anularPorOrigen(user, DOC_TIPO, rec.getId(), motivo);
        rec.setEstado(Recibo.Estado.ANULADO);
        rec.setAnuladoEn(Instant.now());
        rec.setMotivoAnulacion(motivo);
        return ReciboResponse.de(rec);
    }

    public ReciboResponse obtener(Long cia, Long id) {
        return ReciboResponse.de(repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Recibo", id)));
    }

    public Page<ReciboResponse> buscar(Long cia, Long clienteId, LocalDate desde, LocalDate hasta, Long numero, Pageable p) {
        return repo.buscar(cia, clienteId, desde, hasta, numero, p).map(ReciboResponse::de);
    }

    private void abonar(Long cia, Long clienteId, TipoDocumento tipo, Long id, BigDecimal monto) {
        switch (tipo) {
            case FACTURA -> {
                Factura f = facturas.cargar(cia, id);
                validarDocumento(f.getClienteId(), clienteId, f.getEstado() == Factura.Estado.ANULADA, f.getBalance(), monto, "factura " + f.getNumero());
                f.setBalance(f.getBalance().subtract(monto));
                if (f.getBalance().signum() == 0) f.setEstado(Factura.Estado.PAGADA);
            }
            case NOTA_DEBITO -> {
                NotaDebito nd = notasDebito.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Nota de débito", id));
                validarDocumento(nd.getClienteId(), clienteId, false, nd.getBalance(), monto, "nota de débito " + nd.getNumero());
                nd.setBalance(nd.getBalance().subtract(monto));
                if (nd.getBalance().signum() == 0) nd.setEstado(NotaDebito.Estado.PAGADA);
            }
        }
    }

    private void revertir(Long cia, TipoDocumento tipo, Long id, BigDecimal monto) {
        switch (tipo) {
            case FACTURA -> {
                Factura f = facturas.cargar(cia, id);
                if (f.getEstado() == Factura.Estado.ANULADA) {
                    throw new BusinessException("FACTURA_ANULADA", "La factura " + f.getNumero() + " fue anulada; no se puede revertir el cobro");
                }
                f.setBalance(f.getBalance().add(monto));
                f.setEstado(Factura.Estado.EMITIDA);
            }
            case NOTA_DEBITO -> {
                NotaDebito nd = notasDebito.findByIdAndCompaniaId(id, cia).orElseThrow();
                nd.setBalance(nd.getBalance().add(monto));
                nd.setEstado(NotaDebito.Estado.EMITIDA);
            }
        }
    }

    private static void validarDocumento(Long clienteDoc, Long clienteRecibo, boolean anulado, BigDecimal balance,
                                         BigDecimal monto, String nombre) {
        if (!clienteDoc.equals(clienteRecibo)) throw new BusinessException("DOCUMENTO_OTRO_CLIENTE", "La " + nombre + " es de otro cliente");
        if (anulado) throw new BusinessException("DOCUMENTO_ANULADO", "La " + nombre + " está anulada");
        if (monto.compareTo(balance) > 0) {
            throw new BusinessException("MONTO_EXCEDE_BALANCE", "El abono a la " + nombre + " excede su balance RD$" + balance);
        }
    }

    private static BigDecimal cero(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
