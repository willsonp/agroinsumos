package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Cierre del ejercicio: asiento al 31 de diciembre que salda las cuentas de ingreso, costo y gasto del año contra
 * Resultados acumulados (clave RESULTADOS_ACUMULADOS). Después no se aceptan asientos con fecha de ese año;
 * el administrador puede anular el cierre para reabrir el ejercicio (si diciembre sigue abierto).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CierreAnualService {

    public static final String ORIGEN = "CIERRE_ANUAL";

    public record Resultado(int anio, boolean simulacion, BigDecimal ingresos, BigDecimal costos, BigDecimal gastos,
                            BigDecimal resultado, String cuentaDestino, int cuentasSaldadas, int documentosPendientes,
                            List<String> advertencias, Long asientoId) {}

    public record Cierre(int anio, Long asientoId, long numero, LocalDate fecha, BigDecimal total, Asiento.Estado estado,
                         String motivoAnulacion, Instant creadoEn) {}

    private record Saldo(Long cuentaId, TipoCuenta tipo, BigDecimal neto) {}

    private final JdbcTemplate jdbc;
    private final AsientoService asientos;
    private final AsientoRepository repo;
    private final ConfigContableService config;
    private final CuentaContableRepository cuentas;
    private final GeneracionAsientosService generacion;
    private final Clock clock;

    @Transactional
    public Resultado cerrar(AuthUser user, int anio, boolean simular) {
        Long cia = user.companiaId();
        LocalDate inicio = LocalDate.of(anio, 1, 1), fin = LocalDate.of(anio, 12, 31);
        if (LocalDate.now(clock).isBefore(fin)) {
            throw new BusinessException("EJERCICIO_EN_CURSO", "El ejercicio " + anio + " se cierra a partir del 31 de diciembre");
        }
        if (repo.existsByCompaniaIdAndOrigenTipoAndOrigenIdAndEstado(cia, ORIGEN, (long) anio, Asiento.Estado.REGISTRADO)) {
            throw new BusinessException("EJERCICIO_YA_CERRADO", "El ejercicio " + anio + " ya está cerrado");
        }
        String codigoDestino = config.mapa(cia).get(ClaveContable.RESULTADOS_ACUMULADOS);
        if (codigoDestino == null) {
            throw new BusinessException("CUENTA_NO_CONFIGURADA", "Configure la cuenta de " + ClaveContable.RESULTADOS_ACUMULADOS.descripcion());
        }
        var destino = cuentas.findByCompaniaIdAndCodigo(cia, codigoDestino)
                .orElseThrow(() -> new BusinessException("CUENTA_INEXISTENTE", "La cuenta " + codigoDestino + " no existe en el catálogo"));
        if (destino.getTipo() != TipoCuenta.CAPITAL) {
            throw new BusinessException("CUENTA_DESTINO_INVALIDA", "Resultados acumulados debe ser una cuenta de capital");
        }

        // Documentos del año aún sin contabilizar: el cierre los dejaría fuera
        var pendientes = generacion.generar(user, inicio, fin, true);
        int nPendientes = pendientes.asientos() + pendientes.reversos() + pendientes.errores().size();
        List<String> adv = new ArrayList<>();
        if (nPendientes > 0) {
            adv.add(nPendientes + " documento(s) del año sin contabilizar: genere los asientos automáticos antes de cerrar");
            if (!simular) throw new BusinessException("CIERRE_CON_PENDIENTES", adv.getFirst());
        }

        List<Saldo> saldos = jdbc.query("""
                select l.cuenta_id, c.tipo, sum(l.debito - l.credito)
                  from asiento_linea l join asiento a on a.id = l.asiento_id join cuenta_contable c on c.id = l.cuenta_id
                 where a.compania_id = ? and a.estado = 'REGISTRADO' and a.fecha between ? and ?
                   and (a.origen_tipo is null or a.origen_tipo <> ?) and c.tipo in ('INGRESO','COSTO','GASTO')
                 group by l.cuenta_id, c.tipo having sum(l.debito - l.credito) <> 0 order by l.cuenta_id""",
                (rs, i) -> new Saldo(rs.getLong(1), TipoCuenta.valueOf(rs.getString(2)), rs.getBigDecimal(3)),
                cia, Date.valueOf(inicio), Date.valueOf(fin), ORIGEN);
        if (saldos.isEmpty()) throw new BusinessException("SIN_MOVIMIENTOS", "No hay saldos de resultado en " + anio);

        BigDecimal ingresos = BigDecimal.ZERO, costos = BigDecimal.ZERO, gastos = BigDecimal.ZERO, neto = BigDecimal.ZERO;
        List<AsientoService.LineaRequest> lineas = new ArrayList<>();
        for (var s : saldos) {
            neto = neto.add(s.neto());
            switch (s.tipo()) {
                case INGRESO -> ingresos = ingresos.subtract(s.neto());
                case COSTO -> costos = costos.add(s.neto());
                default -> gastos = gastos.add(s.neto());
            }
            // Se salda en sentido contrario a su saldo
            boolean deudor = s.neto().signum() > 0;
            lineas.add(new AsientoService.LineaRequest(s.cuentaId(), "Cierre del ejercicio", deudor ? null : s.neto().negate(),
                    deudor ? s.neto() : null, null, null));
        }
        BigDecimal resultado = neto.negate();   // positivo = utilidad
        if (resultado.signum() != 0) {
            boolean utilidad = resultado.signum() > 0;
            lineas.add(new AsientoService.LineaRequest(destino.getId(), utilidad ? "Utilidad del ejercicio " + anio : "Pérdida del ejercicio " + anio,
                    utilidad ? null : resultado.negate(), utilidad ? resultado : null, null, null));
        }
        var borrador = new AsientoService.Borrador(fin, Asiento.Tipo.AUTOMATICO, ORIGEN, (long) anio, false,
                "Cierre del ejercicio " + anio + ": " + (resultado.signum() >= 0 ? "utilidad" : "pérdida") + " a resultados acumulados", lineas);
        asientos.validar(cia, borrador);
        Long asientoId = simular ? null : asientos.registrar(user, borrador).getId();
        return new Resultado(anio, simular, ingresos, costos, gastos, resultado, destino.getCodigo() + " · " + destino.getNombre(),
                saldos.size(), nPendientes, adv, asientoId);
    }

    /** Reabre el ejercicio anulando su asiento de cierre (diciembre debe estar abierto). */
    @Transactional
    public Cierre anular(AuthUser user, int anio, String motivo) {
        Long cia = user.companiaId();
        var a = repo.findFirstByCompaniaIdAndOrigenTipoAndOrigenIdAndEstado(cia, ORIGEN, (long) anio, Asiento.Estado.REGISTRADO)
                .orElseThrow(() -> new BusinessException("EJERCICIO_ABIERTO", "El ejercicio " + anio + " no está cerrado"));
        asientos.exigirPeriodoAbierto(cia, a.getFecha());
        a.setEstado(Asiento.Estado.ANULADO);
        a.setMotivoAnulacion(motivo);
        return cierre(a);
    }

    public List<Cierre> listar(Long cia) {
        return repo.findByCompaniaIdAndOrigenTipoOrderByOrigenIdDescIdDesc(cia, ORIGEN).stream().map(CierreAnualService::cierre).toList();
    }

    private static Cierre cierre(Asiento a) {
        return new Cierre(a.getOrigenId().intValue(), a.getId(), a.getNumero(), a.getFecha(), a.getTotal(), a.getEstado(),
                a.getMotivoAnulacion(), a.getCreadoEn());
    }
}
