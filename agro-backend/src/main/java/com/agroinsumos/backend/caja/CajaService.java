package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.crud.Rnc;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.EcfEmisionService;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Sesiones de caja por cajero (legacy: Abrir_Caja, Cierra_CajaG, verifica_caja_abierta,
 * Agrega_EgresoCaja, Agrega_Detalle_Cuadre_Caja). Todo cobro o pago en efectivo, tarjeta,
 * cheque o transferencia queda registrado como movimiento de la sesión abierta del usuario.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CajaService {

    /** Billetes y monedas de RD$ (legacy tabla "monedas"). */
    public static final List<BigDecimal> DENOMINACIONES = List.of(2000, 1000, 500, 200, 100, 50, 25, 10, 5, 1)
            .stream().map(BigDecimal::valueOf).toList();

    public record AbrirRequest(@NotNull @DecimalMin("0") BigDecimal montoInicial, Long turnoId) {}

    public record EgresoRequest(@NotBlank @Size(max = 100) String beneficiario, String rnc,
                                @Size(max = 19) String ncf, @NotBlank @Size(max = 200) String concepto,
                                @NotNull @DecimalMin("0.01") BigDecimal monto, @DecimalMin("0") BigDecimal itbis,
                                /** 606: 01..11 (por defecto 02). */
                                @Pattern(regexp = "0[1-9]|1[01]") String tipoBienServicio,
                                /** true: el proveedor no entregó comprobante; emitimos e-CF 43 (gastos menores). */
                                Boolean emitirGastoMenor) {}

    public record IngresoRequest(@NotBlank @Size(max = 200) String concepto,
                                 @NotNull @DecimalMin("0.01") BigDecimal monto) {}

    public record ConteoItem(@NotNull @DecimalMin("0.01") BigDecimal denominacion, @NotNull @Min(0) Integer cantidad) {}

    public record CerrarRequest(@NotNull List<@Valid ConteoItem> conteo, @Size(max = 200) String observacion) {}

    public record TotalForma(FormaPago forma, BigDecimal cobros, BigDecimal ingresos, BigDecimal salidas, BigDecimal neto) {}

    public record Cuadre(BigDecimal montoInicial, List<TotalForma> porForma, BigDecimal efectivoEsperado,
                         BigDecimal efectivoContado, BigDecimal diferencia) {}

    public record MovimientoResponse(Long id, Instant fecha, CajaMovimiento.Tipo tipo, FormaPago forma,
                                     BigDecimal monto, String referenciaTipo, Long referenciaId, String descripcion) {}

    public record SesionResponse(Long id, long numero, Long usuarioId, Long turnoId, Instant abiertaEn,
                                 BigDecimal montoInicial, Instant cerradaEn, CajaSesion.Estado estado,
                                 String observacion, Cuadre cuadre) {}

    public record SesionDetalle(SesionResponse sesion, List<MovimientoResponse> movimientos,
                                List<ConteoItem> conteo) {}

    private final CajaSesionRepository sesiones;
    private final CajaMovimientoRepository movimientos;
    private final CajaConteoRepository conteos;
    private final CajaEgresoRepository egresos;
    private final NumeradorService numerador;
    private final EcfEmisionService ecf;
    private final Clock clock;

    @Transactional
    public SesionResponse abrir(AuthUser user, AbrirRequest r) {
        if (sesiones.findByUsuarioIdAndEstado(user.usuarioId(), CajaSesion.Estado.ABIERTA).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "CAJA_YA_ABIERTA", "Ya tiene una caja abierta; ciérrela primero");
        }
        var s = new CajaSesion();
        s.setCompaniaId(user.companiaId());
        s.setNumero(numerador.siguiente(user.companiaId(), "CAJA"));
        s.setUsuarioId(user.usuarioId());
        s.setTurnoId(r.turnoId());
        s.setMontoInicial(r.montoInicial());
        try {
            sesiones.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) { // dos aperturas simultáneas
            throw new BusinessException(HttpStatus.CONFLICT, "CAJA_YA_ABIERTA", "Ya tiene una caja abierta");
        }
        return respuesta(s);
    }

    public Optional<SesionResponse> actualOpcional(AuthUser user) {
        return abiertaOpcional(user).map(this::respuesta);
    }

    /** Sesión abierta del usuario o error: no se puede cobrar/pagar con la caja cerrada. */
    public CajaSesion abiertaDe(AuthUser user) {
        return sesiones.findByUsuarioIdAndEstado(user.usuarioId(), CajaSesion.Estado.ABIERTA)
                .orElseThrow(() -> new BusinessException("CAJA_CERRADA", "Debe abrir su caja para realizar esta operación"));
    }

    public CajaSesion sesionPorId(Long companiaId, Long id) {
        return sesiones.findByIdAndCompaniaId(id, companiaId).orElseThrow(() -> new NotFoundException("Caja", id));
    }

    public Optional<CajaSesion> abiertaOpcional(AuthUser user) {
        return sesiones.findByUsuarioIdAndEstado(user.usuarioId(), CajaSesion.Estado.ABIERTA);
    }

    /** Registra entrada/salida en la sesión. Lo usan Recibos, Pagos a proveedor, Egresos e Ingresos. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(CajaSesion sesion, CajaMovimiento.Tipo tipo, FormaPago forma, BigDecimal monto,
                          String refTipo, Long refId, String descripcion) {
        if (!sesion.abierta()) throw new BusinessException("CAJA_CERRADA", "La caja " + sesion.getNumero() + " está cerrada");
        if (!forma.pasaPorCaja() || monto.signum() <= 0) return;
        if (tipo.signo() < 0 && forma == FormaPago.EFECTIVO) {
            BigDecimal disponible = cuadre(sesion, null).efectivoEsperado();
            if (monto.compareTo(disponible) > 0) {
                throw new BusinessException("EFECTIVO_INSUFICIENTE", "Efectivo disponible en caja: RD$" + disponible);
            }
        }
        var m = new CajaMovimiento();
        m.setSesionId(sesion.getId());
        m.setTipo(tipo);
        m.setFormaPago(forma);
        m.setMonto(monto);
        m.setReferenciaTipo(refTipo);
        m.setReferenciaId(refId);
        m.setDescripcion(descripcion);
        movimientos.save(m);
    }

    @Transactional
    public MovimientoResponse egreso(AuthUser user, EgresoRequest r) {
        var s = abiertaDe(user);
        var e = new CajaEgreso();
        e.setCompaniaId(user.companiaId());
        e.setNumero(numerador.siguiente(user.companiaId(), "EGRESO_CAJA"));
        e.setSesionId(s.getId());
        e.setFecha(LocalDate.now(clock));
        e.setBeneficiario(r.beneficiario().trim());
        e.setRnc(Rnc.normalizar(r.rnc()));
        e.setNcf(r.ncf() == null || r.ncf().isBlank() ? null : r.ncf().trim().toUpperCase());
        e.setConcepto(r.concepto().trim());
        e.setMonto(r.monto());
        e.setItbis(r.itbis() == null ? BigDecimal.ZERO : r.itbis());
        e.setTipoBienServicio(r.tipoBienServicio() != null ? r.tipoBienServicio() : "02");
        e.setUsuarioId(user.usuarioId());
        boolean emite43 = Boolean.TRUE.equals(r.emitirGastoMenor());
        if (emite43 && (e.getNcf() != null || e.getItbis().signum() > 0)) {
            throw new BusinessException("GASTO_MENOR_INVALIDO", "El e-CF 43 se emite solo cuando no hay comprobante del proveedor y sin ITBIS");
        }
        egresos.save(e);
        if (emite43) {
            String concepto = e.getConcepto().length() > 80 ? e.getConcepto().substring(0, 80) : e.getConcepto();
            var emitido = ecf.emitir(user.companiaId(), new EcfDtos.EmitirEcfRequest(TipoEcf.GASTOS_MENORES.codigo(),
                    e.getFecha(), null, 1, null, null,
                    List.of(new EcfDtos.Item(concepto, 2, BigDecimal.ONE, e.getMonto(), null, 4)), null, "EGRESO_CAJA", e.getId()));
            e.setNcf(emitido.encf());
            e.setEcfDocumentoId(emitido.id());
        }
        registrar(s, CajaMovimiento.Tipo.EGRESO, FormaPago.EFECTIVO, r.monto(), "EGRESO_CAJA", e.getId(),
                e.getBeneficiario() + ": " + e.getConcepto());
        return ultimo(s);
    }

    @Transactional
    public MovimientoResponse ingreso(AuthUser user, IngresoRequest r) {
        var s = abiertaDe(user);
        registrar(s, CajaMovimiento.Tipo.INGRESO, FormaPago.EFECTIVO, r.monto(), "INGRESO_CAJA", s.getId(), r.concepto().trim());
        return ultimo(s);
    }

    @Transactional
    public SesionResponse cerrar(AuthUser user, CerrarRequest r) {
        var s = abiertaDe(user);
        Set<BigDecimal> vistas = new HashSet<>();
        BigDecimal contado = BigDecimal.ZERO;
        for (var c : r.conteo()) {
            if (!vistas.add(c.denominacion().stripTrailingZeros())) {
                throw new BusinessException("DENOMINACION_REPETIDA", "Denominación repetida: " + c.denominacion());
            }
            if (c.cantidad() == 0) continue;
            var cc = new CajaConteo();
            cc.setSesionId(s.getId());
            cc.setDenominacion(c.denominacion());
            cc.setCantidad(c.cantidad());
            conteos.save(cc);
            contado = contado.add(c.denominacion().multiply(BigDecimal.valueOf(c.cantidad())));
        }
        Cuadre q = cuadre(s, contado);
        s.setEfectivoEsperado(q.efectivoEsperado());
        s.setEfectivoContado(contado);
        s.setDiferencia(q.diferencia());
        s.setObservacion(r.observacion());
        s.setCerradaEn(Instant.now());
        s.setEstado(CajaSesion.Estado.CERRADA);
        return respuesta(s);
    }

    public Page<SesionResponse> listar(Long companiaId, Long usuarioId, CajaSesion.Estado estado, Pageable p) {
        return sesiones.buscar(companiaId, usuarioId, estado, p).map(this::respuesta);
    }

    /** Detalle para el reporte de cierre (arqueo). */
    public SesionDetalle detalle(AuthUser user, Long id) {
        var s = sesiones.findByIdAndCompaniaId(id, user.companiaId()).orElseThrow(() -> new NotFoundException("Caja", id));
        boolean supervisor = user.nivel().name().equals("ADMIN") || user.nivel().name().equals("SUPERVISOR");
        if (!supervisor && !s.getUsuarioId().equals(user.usuarioId())) throw new NotFoundException("Caja", id);
        return new SesionDetalle(respuesta(s),
                movimientos.findBySesionIdOrderById(s.getId()).stream().map(CajaService::mov).toList(),
                conteos.findBySesionIdOrderByDenominacionDesc(s.getId()).stream()
                        .map(c -> new ConteoItem(c.getDenominacion(), c.getCantidad())).toList());
    }

    Cuadre cuadre(CajaSesion s, BigDecimal contado) {
        Map<FormaPago, BigDecimal[]> acc = new EnumMap<>(FormaPago.class);
        for (Object[] row : movimientos.totales(s.getId())) {
            var forma = (FormaPago) row[0];
            var tipo = (CajaMovimiento.Tipo) row[1];
            var monto = (BigDecimal) row[2];
            var a = acc.computeIfAbsent(forma, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            switch (tipo) {
                case COBRO -> a[0] = a[0].add(monto);
                case ANULACION_COBRO -> a[0] = a[0].subtract(monto);
                case INGRESO, ANULACION_PAGO -> a[1] = a[1].add(monto);
                case EGRESO, PAGO_PROVEEDOR -> a[2] = a[2].add(monto);
            }
        }
        List<TotalForma> porForma = acc.entrySet().stream()
                .map(e -> new TotalForma(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[0].add(e.getValue()[1]).subtract(e.getValue()[2])))
                .toList();
        BigDecimal efectivo = s.getMontoInicial().add(porForma.stream().filter(t -> t.forma() == FormaPago.EFECTIVO)
                .map(TotalForma::neto).findFirst().orElse(BigDecimal.ZERO));
        if (!s.abierta() && contado == null) contado = s.getEfectivoContado();
        return new Cuadre(s.getMontoInicial(), porForma, efectivo, contado, contado == null ? null : contado.subtract(efectivo));
    }

    private MovimientoResponse ultimo(CajaSesion s) {
        var l = movimientos.findBySesionIdOrderById(s.getId());
        return mov(l.getLast());
    }

    private static MovimientoResponse mov(CajaMovimiento m) {
        return new MovimientoResponse(m.getId(), m.getFecha(), m.getTipo(), m.getFormaPago(), m.getMonto(),
                m.getReferenciaTipo(), m.getReferenciaId(), m.getDescripcion());
    }

    private SesionResponse respuesta(CajaSesion s) {
        return new SesionResponse(s.getId(), s.getNumero(), s.getUsuarioId(), s.getTurnoId(), s.getAbiertaEn(),
                s.getMontoInicial(), s.getCerradaEn(), s.getEstado(), s.getObservacion(), cuadre(s, null));
    }
}
