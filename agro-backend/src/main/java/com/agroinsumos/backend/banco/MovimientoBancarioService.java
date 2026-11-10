package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.caja.CajaMovimiento;
import com.agroinsumos.backend.caja.CajaService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * Movimientos bancarios (legacy transaccionesbancarias / FrmTransaccionesBanco). Los pagos a
 * suplidores por cheque o transferencia y los cobros por transferencia generan su movimiento
 * automáticamente (origen PAGO_PROVEEDOR / RECIBO).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovimientoBancarioService {

    public static final String MANUAL = "MANUAL";
    public static final String DEPOSITO_CAJA = "DEPOSITO_CAJA";
    public static final String TRANSFERENCIA = "TRANSFERENCIA";
    private static final Set<TipoMovimientoBancario> MANUALES = Set.of(TipoMovimientoBancario.DEPOSITO,
            TipoMovimientoBancario.CHEQUE, TipoMovimientoBancario.NOTA_DEBITO, TipoMovimientoBancario.NOTA_CREDITO);

    public record RegistrarRequest(@NotNull Long cuentaId, @NotNull TipoMovimientoBancario tipo, LocalDate fecha,
                                   @Size(max = 40) String referencia, @Size(max = 100) String beneficiario,
                                   @NotBlank @Size(max = 200) String concepto, @NotNull @DecimalMin("0.01") BigDecimal monto,
                                   /** Solo DEPOSITO: el efectivo sale de la caja abierta del usuario. */
                                   Boolean desdeCaja) {}

    public record TransferenciaRequest(@NotNull Long cuentaOrigenId, @NotNull Long cuentaDestinoId, LocalDate fecha,
                                       @NotNull @DecimalMin("0.01") BigDecimal monto, @NotBlank @Size(max = 200) String concepto,
                                       @Size(max = 40) String referencia) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record Response(Long id, long numero, Long cuentaId, LocalDate fecha, TipoMovimientoBancario tipo, String referencia,
                           String beneficiario, String concepto, BigDecimal monto, BigDecimal montoConSigno,
                           MovimientoBancario.Estado estado, Long conciliacionId, String origenTipo, Long origenId,
                           Long transferenciaParId, String motivoAnulacion) {
        public static Response de(MovimientoBancario m) {
            return new Response(m.getId(), m.getNumero(), m.getCuentaId(), m.getFecha(), m.getTipo(), m.getReferencia(),
                    m.getBeneficiario(), m.getConcepto(), m.getMonto(), m.montoConSigno(), m.getEstado(), m.getConciliacionId(),
                    m.getOrigenTipo(), m.getOrigenId(), m.getTransferenciaParId(), m.getMotivoAnulacion());
        }
    }

    private final MovimientoBancarioRepository repo;
    private final CuentaBancariaRepository cuentas;
    private final CuentaBancariaService cuentaService;
    private final ConciliacionBancariaRepository conciliaciones;
    private final CajaService caja;
    private final NumeradorService numerador;
    private final Clock clock;

    @Value("${app.banco.permitir-sobregiro:false}")
    private boolean permitirSobregiro;

    /** Registro manual: depósito (opcionalmente desde la caja), cheque, nota de débito o de crédito. */
    @Transactional
    public Response registrarManual(AuthUser user, RegistrarRequest r) {
        if (!MANUALES.contains(r.tipo())) {
            throw new BusinessException("TIPO_INVALIDO", "Use el endpoint de transferencias para mover dinero entre cuentas");
        }
        boolean desdeCaja = Boolean.TRUE.equals(r.desdeCaja());
        if (desdeCaja && r.tipo() != TipoMovimientoBancario.DEPOSITO) {
            throw new BusinessException("TIPO_INVALIDO", "Solo un depósito puede salir de la caja");
        }
        var sesion = desdeCaja ? caja.abiertaDe(user) : null;
        var m = registrar(user, r.cuentaId(), r.tipo(), r.fecha(), r.referencia(), r.beneficiario(), r.concepto(), r.monto(),
                desdeCaja ? DEPOSITO_CAJA : MANUAL, desdeCaja ? sesion.getId() : null);
        if (desdeCaja) {
            caja.registrar(sesion, CajaMovimiento.Tipo.EGRESO, FormaPago.EFECTIVO, r.monto(), "DEPOSITO_BANCO", m.getId(),
                    "Depósito bancario " + (m.getReferencia() == null ? "" : m.getReferencia()));
        }
        return Response.de(m);
    }

    /** Transferencia entre cuentas propias: genera la salida y la entrada enlazadas. */
    @Transactional
    public Response transferir(AuthUser user, TransferenciaRequest r) {
        if (r.cuentaOrigenId().equals(r.cuentaDestinoId())) {
            throw new BusinessException("TRANSFERENCIA_INVALIDA", "La cuenta origen y destino deben ser distintas");
        }
        // Bloqueo en orden de id para evitar interbloqueos entre transferencias cruzadas
        bloquear(user.companiaId(), Math.min(r.cuentaOrigenId(), r.cuentaDestinoId()));
        bloquear(user.companiaId(), Math.max(r.cuentaOrigenId(), r.cuentaDestinoId()));
        var salida = registrar(user, r.cuentaOrigenId(), TipoMovimientoBancario.TRANSFERENCIA_SALIDA, r.fecha(), r.referencia(),
                null, r.concepto(), r.monto(), TRANSFERENCIA, null);
        var entrada = registrar(user, r.cuentaDestinoId(), TipoMovimientoBancario.TRANSFERENCIA_ENTRADA, r.fecha(), r.referencia(),
                null, r.concepto(), r.monto(), TRANSFERENCIA, null);
        salida.setTransferenciaParId(entrada.getId());
        entrada.setTransferenciaParId(salida.getId());
        return Response.de(salida);
    }

    /**
     * Núcleo: valida cuenta, fecha (posterior a la última conciliación), saldo y numeración de cheques.
     * Lo usan también los pagos a suplidores y los recibos.
     */
    @Transactional
    public MovimientoBancario registrar(AuthUser user, Long cuentaId, TipoMovimientoBancario tipo, LocalDate fecha,
                                       String referencia, String beneficiario, String concepto, BigDecimal monto,
                                       String origenTipo, Long origenId) {
        Long cia = user.companiaId();
        CuentaBancaria cuenta = bloquear(cia, cuentaId);
        if (!"A".equals(cuenta.getEstado())) throw new BusinessException("CUENTA_INACTIVA", "La cuenta " + cuenta.getNumero() + " está inactiva");
        LocalDate f = fecha != null ? fecha : LocalDate.now(clock);
        if (f.isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha no puede ser futura");
        if (f.isBefore(cuenta.getFechaSaldoInicial())) {
            throw new BusinessException("FECHA_INVALIDA", "La fecha es anterior al saldo inicial de la cuenta (" + cuenta.getFechaSaldoInicial() + ")");
        }
        conciliaciones.ultimaFechaCorte(cuenta.getId()).ifPresent(corte -> {
            if (!f.isAfter(corte)) {
                throw new BusinessException("PERIODO_CONCILIADO", "La cuenta está conciliada hasta " + corte + "; registre con fecha posterior");
            }
        });
        if (!tipo.entrada() && !permitirSobregiro) {
            BigDecimal saldo = cuentaService.saldo(cuenta, null);
            if (saldo.compareTo(monto) < 0) {
                throw new BusinessException("SALDO_INSUFICIENTE", "Saldo disponible en " + cuenta.getNombre() + ": RD$" + saldo);
            }
        }
        String ref = referencia == null || referencia.isBlank() ? null : referencia.trim();
        if (tipo == TipoMovimientoBancario.CHEQUE) {
            if (ref == null) {
                ref = String.valueOf(cuenta.getProximoCheque());
                cuenta.setProximoCheque(cuenta.getProximoCheque() + 1);
            } else if (ref.matches("\\d{1,15}") && Long.parseLong(ref) >= cuenta.getProximoCheque()) {
                cuenta.setProximoCheque(Long.parseLong(ref) + 1);
            }
        }
        var m = new MovimientoBancario();
        m.setCompaniaId(cia);
        m.setCuentaId(cuenta.getId());
        m.setNumero(numerador.siguiente(cia, "MOV_BANCO"));
        m.setFecha(f);
        m.setTipo(tipo);
        m.setReferencia(ref);
        m.setBeneficiario(beneficiario);
        m.setConcepto(concepto.trim());
        m.setMonto(monto);
        m.setOrigenTipo(origenTipo);
        m.setOrigenId(origenId);
        m.setUsuarioId(user.usuarioId());
        try {
            return repo.saveAndFlush(m);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "CHEQUE_DUPLICADO", "El cheque " + ref + " ya fue emitido en esta cuenta");
        }
    }

    /** Anulación manual (supervisor). Los movimientos de pagos/recibos se anulan anulando su documento. */
    @Transactional
    public Response anular(AuthUser user, Long id, String motivo) {
        var m = cargar(user.companiaId(), id);
        if (m.getOrigenTipo() != null && !Set.of(MANUAL, DEPOSITO_CAJA, TRANSFERENCIA).contains(m.getOrigenTipo())) {
            throw new BusinessException("ANULAR_DESDE_ORIGEN", "Este movimiento proviene de " + m.getOrigenTipo() + " " + m.getOrigenId()
                    + "; anule ese documento");
        }
        anularMovimiento(user, m, motivo);
        if (m.getTransferenciaParId() != null) anularMovimiento(user, cargar(user.companiaId(), m.getTransferenciaParId()), motivo);
        return Response.de(m);
    }

    /** Usado al anular un pago a suplidor o un recibo. */
    @Transactional
    public void anularPorOrigen(AuthUser user, String origenTipo, Long origenId, String motivo) {
        for (var m : repo.findByOrigenTipoAndOrigenIdAndEstado(origenTipo, origenId, MovimientoBancario.Estado.REGISTRADO)) {
            anularMovimiento(user, m, motivo);
        }
    }

    private void anularMovimiento(AuthUser user, MovimientoBancario m, String motivo) {
        if (m.getEstado() == MovimientoBancario.Estado.ANULADO) throw new BusinessException("MOVIMIENTO_ANULADO", "El movimiento ya está anulado");
        if (m.getConciliacionId() != null) {
            throw new BusinessException("MOVIMIENTO_CONCILIADO", "El movimiento " + m.getNumero() + " ya está conciliado; registre un movimiento inverso");
        }
        if (DEPOSITO_CAJA.equals(m.getOrigenTipo())) {
            var sesion = caja.sesionPorId(user.companiaId(), m.getOrigenId());
            caja.registrar(sesion, CajaMovimiento.Tipo.INGRESO, FormaPago.EFECTIVO, m.getMonto(), "DEPOSITO_BANCO", m.getId(),
                    "Anulación depósito bancario " + m.getNumero());
        }
        m.setEstado(MovimientoBancario.Estado.ANULADO);
        m.setAnuladoEn(Instant.now());
        m.setMotivoAnulacion(motivo);
    }

    public Page<Response> buscar(Long cia, Long cuentaId, TipoMovimientoBancario tipo, LocalDate desde, LocalDate hasta, Pageable p) {
        return repo.buscar(cia, cuentaId, tipo, desde, hasta, p).map(Response::de);
    }

    private MovimientoBancario cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Movimiento bancario", id));
    }

    private CuentaBancaria bloquear(Long cia, Long cuentaId) {
        return cuentas.bloquear(cuentaId, cia).orElseThrow(() -> new NotFoundException("Cuenta bancaria", cuentaId));
    }
}
