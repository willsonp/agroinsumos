package com.agroinsumos.backend.caja.chica;

import com.agroinsumos.backend.banco.CuentaBancariaRepository;
import com.agroinsumos.backend.banco.MovimientoBancarioService;
import com.agroinsumos.backend.banco.TipoMovimientoBancario;
import com.agroinsumos.backend.caja.CajaMovimiento;
import com.agroinsumos.backend.caja.CajaService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.crud.Rnc;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.common.pago.FormaPago;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Caja chica de fondo fijo: los gastos (vales) rebajan el saldo; la reposición devuelve al fondo exactamente lo gastado
 * (cheque, transferencia o efectivo de la caja) y deja los gastos marcados como repuestos. Legacy: FrmCuadreCajaChica.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CajaChicaService {

    public static final String ORIGEN = "CAJA_CHICA";

    public record Origen(@NotNull String forma, Long cuentaBancariaId, @Size(max = 40) String referencia) {}

    public record FondoRequest(@NotBlank @Size(max = 60) String nombre, Long responsableId,
                               @NotNull @DecimalMin("1") BigDecimal montoFondo, @Size(max = 14) String cuentaContable,
                               /** Solo al crear: de dónde sale el dinero de la apertura. */ Origen apertura) {}

    public record GastoRequest(LocalDate fecha, @NotBlank @Size(max = 200) String concepto, @Size(max = 100) String beneficiario,
                               @Size(max = 11) String rnc, @Size(max = 19) String ncf, @Pattern(regexp = "0[1-9]|1[01]") String tipoBienServicio,
                               @NotNull @DecimalMin("0.01") BigDecimal monto, @DecimalMin("0") BigDecimal itbis, @Size(max = 14) String cuentaGasto) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record ArqueoRequest(@NotNull @DecimalMin("0") BigDecimal efectivoContado, @Size(max = 200) String observacion) {}

    public record FondoResponse(Long id, String nombre, Long responsableId, String responsable, BigDecimal montoFondo, BigDecimal saldo,
                                BigDecimal porReponer, int gastosPendientes, String cuentaContable, String estado) {}

    public record MovimientoResponse(Long id, long numero, LocalDate fecha, CajaChicaMovimiento.Tipo tipo, String concepto,
                                     String beneficiario, String rnc, String ncf, BigDecimal monto, BigDecimal itbis, String cuentaGasto,
                                     String forma, Long reposicionId, CajaChicaMovimiento.Estado estado, String motivoAnulacion) {
        static MovimientoResponse de(CajaChicaMovimiento m) {
            return new MovimientoResponse(m.getId(), m.getNumero(), m.getFecha(), m.getTipo(), m.getConcepto(), m.getBeneficiario(),
                    m.getRnc(), m.getNcf(), m.getMonto(), m.getItbis(), m.getCuentaGasto(), m.getForma(), m.getReposicionId(),
                    m.getEstado(), m.getMotivoAnulacion());
        }
    }

    public record Reposicion(MovimientoResponse reposicion, FondoResponse fondo, List<MovimientoResponse> gastos, BigDecimal itbis) {}

    public record Arqueo(Long id, Instant fecha, BigDecimal efectivoContado, BigDecimal saldoSistema, BigDecimal diferencia, String observacion) {}

    private final CajaChicaRepository fondos;
    private final CajaChicaMovimientoRepository movimientos;
    private final CajaService caja;
    private final MovimientoBancarioService banco;
    private final CuentaBancariaRepository cuentasBancarias;
    private final NumeradorService numerador;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public FondoResponse crear(AuthUser user, FondoRequest r) {
        Long cia = user.companiaId();
        if (fondos.existsByCompaniaIdAndNombreIgnoreCase(cia, r.nombre().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICADO", "Ya existe la caja chica " + r.nombre());
        }
        if (r.apertura() == null) throw new BusinessException("ORIGEN_REQUERIDO", "Indique de dónde sale el dinero del fondo");
        var f = new CajaChica();
        f.setCompaniaId(cia);
        f.setNombre(r.nombre().trim());
        f.setResponsableId(r.responsableId());
        f.setMontoFondo(r.montoFondo());
        f.setCuentaContable(vacio(r.cuentaContable()));
        fondos.saveAndFlush(f);
        entrada(user, f, CajaChicaMovimiento.Tipo.APERTURA, r.montoFondo(), "Apertura del fondo " + f.getNombre(), r.apertura());
        return respuesta(f);
    }

    @Transactional
    public FondoResponse actualizar(AuthUser user, Long id, FondoRequest r) {
        var f = bloquear(user.companiaId(), id);
        f.setNombre(r.nombre().trim());
        f.setResponsableId(r.responsableId());
        f.setCuentaContable(vacio(r.cuentaContable()));
        if (r.montoFondo().compareTo(f.getMontoFondo()) != 0) {
            throw new BusinessException("MONTO_FONDO_FIJO", "El monto del fondo no se cambia aquí: cree otro fondo o ajústelo con una reposición");
        }
        return respuesta(f);
    }

    public List<FondoResponse> listar(Long cia) { return fondos.findByCompaniaIdOrderByNombre(cia).stream().map(this::respuesta).toList(); }

    @Transactional
    public MovimientoResponse gasto(AuthUser user, Long fondoId, GastoRequest r) {
        Long cia = user.companiaId();
        var f = bloquear(cia, fondoId);
        if (!"A".equals(f.getEstado())) throw new BusinessException("FONDO_INACTIVO", "La caja chica está inactiva");
        LocalDate fecha = r.fecha() != null ? r.fecha() : LocalDate.now(clock);
        if (fecha.isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha no puede ser futura");
        BigDecimal itbis = r.itbis() == null ? BigDecimal.ZERO : r.itbis();
        if (itbis.compareTo(r.monto()) >= 0) throw new BusinessException("ITBIS_INVALIDO", "El ITBIS no puede ser igual o mayor que el monto");
        if (r.monto().compareTo(f.getSaldo()) > 0) {
            throw new BusinessException("SALDO_INSUFICIENTE", "Saldo disponible en " + f.getNombre() + ": RD$" + f.getSaldo() + "; solicite la reposición");
        }
        String ncf = vacio(r.ncf());
        if (ncf != null) {
            ncf = ncf.toUpperCase();
            if (!ncf.matches("B\\d{10}|E\\d{12}")) throw new BusinessException("NCF_INVALIDO", "NCF inválido: " + r.ncf());
        }
        String rnc = vacio(r.rnc()) == null ? null : Rnc.normalizar(r.rnc());
        if (ncf != null && rnc == null) throw new BusinessException("RNC_REQUERIDO", "Con NCF indique el RNC o cédula del suplidor (606)");
        if (ncf != null && movimientos.ncfRegistrado(cia, rnc, ncf)) {
            throw new BusinessException("NCF_DUPLICADO", "El NCF " + ncf + " del suplidor " + rnc + " ya está registrado");
        }
        var m = nuevo(user, f, CajaChicaMovimiento.Tipo.GASTO, fecha, r.concepto().trim(), r.monto());
        m.setBeneficiario(vacio(r.beneficiario()));
        m.setRnc(rnc);
        m.setNcf(ncf);
        m.setTipoBienServicio(r.tipoBienServicio() == null ? "02" : r.tipoBienServicio());
        m.setItbis(itbis);
        m.setCuentaGasto(vacio(r.cuentaGasto()));
        movimientos.save(m);
        f.setSaldo(f.getSaldo().subtract(r.monto()));
        return MovimientoResponse.de(m);
    }

    /** Solo gastos no repuestos (supervisor): el monto vuelve al saldo. */
    @Transactional
    public MovimientoResponse anularGasto(AuthUser user, Long id, String motivo) {
        Long cia = user.companiaId();
        var m = movimientos.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Movimiento de caja chica", id));
        if (m.getTipo() != CajaChicaMovimiento.Tipo.GASTO) throw new BusinessException("SOLO_GASTOS", "Solo se anulan gastos");
        if (m.getEstado() == CajaChicaMovimiento.Estado.ANULADO) throw new BusinessException("YA_ANULADO", "El gasto ya está anulado");
        if (m.getReposicionId() != null) throw new BusinessException("GASTO_REPUESTO", "El gasto ya fue repuesto; regístrelo como corrección en la próxima reposición");
        var f = bloquear(cia, m.getCajaChicaId());
        m.setEstado(CajaChicaMovimiento.Estado.ANULADO);
        m.setMotivoAnulacion(motivo);
        f.setSaldo(f.getSaldo().add(m.getMonto()));
        return MovimientoResponse.de(m);
    }

    /** Repone lo gastado (gastos pendientes) y deja el fondo completo. */
    @Transactional
    public Reposicion reponer(AuthUser user, Long fondoId, Origen origen) {
        Long cia = user.companiaId();
        var f = bloquear(cia, fondoId);
        var gastos = movimientos.pendientesDeReponer(f.getId());
        if (gastos.isEmpty()) throw new BusinessException("NADA_QUE_REPONER", "No hay gastos pendientes de reponer");
        BigDecimal total = gastos.stream().map(CajaChicaMovimiento::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
        var rep = entrada(user, f, CajaChicaMovimiento.Tipo.REPOSICION, total,
                "Reposición de " + gastos.size() + " gasto(s) de " + f.getNombre(), origen);
        gastos.forEach(g -> g.setReposicionId(rep.getId()));
        return reposicion(cia, rep.getId());
    }

    public Reposicion reposicion(Long cia, Long id) {
        var rep = movimientos.findByIdAndCompaniaId(id, cia).filter(m -> m.getTipo() == CajaChicaMovimiento.Tipo.REPOSICION)
                .orElseThrow(() -> new NotFoundException("Reposición", id));
        var gastos = movimientos.findByReposicionIdOrderByFechaAscNumeroAsc(id);
        return new Reposicion(MovimientoResponse.de(rep), respuesta(fondos.findById(rep.getCajaChicaId()).orElseThrow()),
                gastos.stream().map(MovimientoResponse::de).toList(),
                gastos.stream().map(CajaChicaMovimiento::getItbis).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public List<MovimientoResponse> movimientos(Long cia, Long fondoId, LocalDate desde, LocalDate hasta) {
        cargar(cia, fondoId);
        return movimientos.buscar(fondoId, desde, hasta).stream().map(MovimientoResponse::de).toList();
    }

    @Transactional
    public Arqueo arqueo(AuthUser user, Long fondoId, ArqueoRequest r) {
        var f = cargar(user.companiaId(), fondoId);
        BigDecimal dif = r.efectivoContado().subtract(f.getSaldo());
        Long id = jdbc.queryForObject("""
                insert into caja_chica_arqueo (caja_chica_id, efectivo_contado, saldo_sistema, diferencia, observacion, usuario_id)
                values (?, ?, ?, ?, ?, ?) returning id""", Long.class, f.getId(), r.efectivoContado(), f.getSaldo(), dif,
                vacio(r.observacion()), user.usuarioId());
        return new Arqueo(id, Instant.now(), r.efectivoContado(), f.getSaldo(), dif, vacio(r.observacion()));
    }

    public List<Arqueo> arqueos(Long cia, Long fondoId) {
        cargar(cia, fondoId);
        return jdbc.query("""
                select id, fecha, efectivo_contado, saldo_sistema, diferencia, observacion from caja_chica_arqueo
                 where caja_chica_id = ? order by fecha desc limit 50""",
                (rs, i) -> new Arqueo(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getString(6)), fondoId);
    }

    // ------------------------------------------------------------------ apoyo
    /** Apertura o reposición: el dinero sale del banco (cheque / transferencia) o del efectivo de la caja del usuario. */
    private CajaChicaMovimiento entrada(AuthUser user, CajaChica f, CajaChicaMovimiento.Tipo tipo, BigDecimal monto, String concepto, Origen o) {
        var m = nuevo(user, f, tipo, LocalDate.now(clock), concepto, monto);
        m.setForma(o.forma());
        switch (o.forma()) {
            case "EFECTIVO" -> {
                var sesion = caja.abiertaDe(user);
                m.setSesionCajaId(sesion.getId());
                movimientos.saveAndFlush(m);
                caja.registrar(sesion, CajaMovimiento.Tipo.EGRESO, FormaPago.EFECTIVO, monto, ORIGEN, m.getId(), concepto);
            }
            case "CHEQUE", "TRANSFERENCIA" -> {
                if (o.cuentaBancariaId() == null) throw new BusinessException("CUENTA_REQUERIDA", "Indique la cuenta bancaria");
                var cuenta = cuentasBancarias.findById(o.cuentaBancariaId()).filter(c -> c.getCompaniaId().equals(f.getCompaniaId()))
                        .orElseThrow(() -> new NotFoundException("Cuenta bancaria", o.cuentaBancariaId()));
                if (!"DOP".equals(cuenta.getMoneda())) throw new BusinessException("CUENTA_OTRA_MONEDA", "La caja chica se repone desde cuentas en pesos");
                m.setCuentaBancariaId(cuenta.getId());
                movimientos.saveAndFlush(m);
                banco.registrar(user, cuenta.getId(), "CHEQUE".equals(o.forma()) ? TipoMovimientoBancario.CHEQUE : TipoMovimientoBancario.TRANSFERENCIA_SALIDA,
                        m.getFecha(), o.referencia(), f.getNombre(), concepto, monto, ORIGEN, m.getId());
            }
            default -> throw new BusinessException("FORMA_INVALIDA", "Forma: EFECTIVO, CHEQUE o TRANSFERENCIA");
        }
        f.setSaldo(f.getSaldo().add(monto));
        return m;
    }

    private CajaChicaMovimiento nuevo(AuthUser user, CajaChica f, CajaChicaMovimiento.Tipo tipo, LocalDate fecha, String concepto, BigDecimal monto) {
        var m = new CajaChicaMovimiento();
        m.setCompaniaId(f.getCompaniaId());
        m.setCajaChicaId(f.getId());
        m.setNumero(numerador.siguiente(f.getCompaniaId(), ORIGEN));
        m.setFecha(fecha);
        m.setTipo(tipo);
        m.setConcepto(concepto.length() > 200 ? concepto.substring(0, 200) : concepto);
        m.setMonto(monto);
        m.setUsuarioId(user.usuarioId());
        return m;
    }

    private CajaChica cargar(Long cia, Long id) {
        return fondos.findById(id).filter(f -> f.getCompaniaId().equals(cia)).orElseThrow(() -> new NotFoundException("Caja chica", id));
    }

    private CajaChica bloquear(Long cia, Long id) {
        return fondos.bloquear(id, cia).orElseThrow(() -> new NotFoundException("Caja chica", id));
    }

    private FondoResponse respuesta(CajaChica f) {
        String responsable = f.getResponsableId() == null ? null
                : jdbc.query("select coalesce(nullif(trim(concat_ws(' ', nombre, apellidos)), ''), username) from usuario where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, f.getResponsableId());
        int pendientes = f.getId() == null ? 0 : movimientos.pendientesDeReponer(f.getId()).size();
        return new FondoResponse(f.getId(), f.getNombre(), f.getResponsableId(), responsable, f.getMontoFondo(), f.getSaldo(),
                f.porReponer(), pendientes, f.getCuentaContable(), f.getEstado());
    }

    private static String vacio(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
