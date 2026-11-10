package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Balanza de comprobación, estado de resultados, balance general y mayor (legacy tmpbalanza, tmpestado, tmparbol*).
 * Los saldos de las cuentas de detalle se acumulan hacia sus cuentas de grupo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportesContablesService {

    /** saldos con signo deudor (débito − crédito). */
    public record FilaBalanza(Long cuentaId, String codigo, String nombre, TipoCuenta tipo, int nivel, boolean detalle,
                              BigDecimal saldoAnterior, BigDecimal debitos, BigDecimal creditos, BigDecimal saldoFinal) {}

    public record Balanza(LocalDate desde, LocalDate hasta, List<FilaBalanza> cuentas, BigDecimal totalDebitos,
                          BigDecimal totalCreditos, BigDecimal totalSaldoAnterior, BigDecimal totalSaldoFinal, boolean cuadra) {}

    /** monto en la naturaleza de la sección (positivo = normal). */
    public record Fila(Long cuentaId, String codigo, String nombre, int nivel, boolean detalle, BigDecimal monto) {}

    public record Seccion(TipoCuenta tipo, String titulo, List<Fila> cuentas, BigDecimal total) {}

    public record EstadoResultados(LocalDate desde, LocalDate hasta, Seccion ingresos, Seccion costos, BigDecimal utilidadBruta,
                                   Seccion gastos, BigDecimal utilidadNeta) {}

    public record BalanceGeneral(LocalDate corte, Seccion activos, Seccion pasivos, Seccion capital, BigDecimal resultadoAcumulado,
                                 BigDecimal totalPasivoCapital, boolean cuadra) {}

    public record LineaMayor(LocalDate fecha, Long asientoId, long numero, String concepto, String descripcion,
                             BigDecimal debito, BigDecimal credito, BigDecimal saldo) {}

    public record Mayor(Long cuentaId, String codigo, String nombre, TipoCuenta tipo, LocalDate desde, LocalDate hasta,
                        BigDecimal saldoAnterior, List<LineaMayor> lineas, BigDecimal saldoFinal) {}

    private record Mov(BigDecimal debitos, BigDecimal creditos) {
        static final Mov CERO = new Mov(BigDecimal.ZERO, BigDecimal.ZERO);
        Mov mas(Mov o) { return new Mov(debitos.add(o.debitos), creditos.add(o.creditos)); }
        BigDecimal neto() { return debitos.subtract(creditos); }
    }

    private final JdbcTemplate jdbc;
    private final CuentaContableRepository cuentas;

    public Balanza balanza(Long cia, LocalDate desde, LocalDate hasta, boolean incluirSinMovimiento) {
        rango(desde, hasta);
        var plan = cuentas.findByCompaniaIdOrderByCodigo(cia);
        var anterior = acumular(plan, movimientos(cia, null, desde.minusDays(1)));
        var periodo = acumular(plan, movimientos(cia, desde, hasta));
        List<FilaBalanza> filas = new ArrayList<>();
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO, ta = BigDecimal.ZERO, tf = BigDecimal.ZERO;
        for (var c : plan) {
            Mov a = anterior.getOrDefault(c.getId(), Mov.CERO), p = periodo.getOrDefault(c.getId(), Mov.CERO);
            boolean vacia = a.neto().signum() == 0 && p.debitos().signum() == 0 && p.creditos().signum() == 0;
            if (vacia && !incluirSinMovimiento) continue;
            BigDecimal saldoFinal = a.neto().add(p.neto());
            filas.add(new FilaBalanza(c.getId(), c.getCodigo(), c.getNombre(), c.getTipo(), c.getNivel(), c.isAceptaMovimiento(),
                    a.neto(), p.debitos(), p.creditos(), saldoFinal));
            if (c.isAceptaMovimiento()) {
                td = td.add(p.debitos()); tc = tc.add(p.creditos()); ta = ta.add(a.neto()); tf = tf.add(saldoFinal);
            }
        }
        return new Balanza(desde, hasta, filas, td, tc, ta, tf, td.compareTo(tc) == 0 && tf.signum() == 0);
    }

    public EstadoResultados estadoResultados(Long cia, LocalDate desde, LocalDate hasta) {
        rango(desde, hasta);
        var plan = cuentas.findByCompaniaIdOrderByCodigo(cia);
        // Sin el asiento de cierre anual, que deja en cero las cuentas de resultado
        var mov = acumular(plan, movimientos(cia, desde, hasta, true));
        var ingresos = seccion(plan, mov, TipoCuenta.INGRESO, "Ingresos");
        var costos = seccion(plan, mov, TipoCuenta.COSTO, "Costos");
        var gastos = seccion(plan, mov, TipoCuenta.GASTO, "Gastos");
        BigDecimal bruta = ingresos.total().subtract(costos.total());
        return new EstadoResultados(desde, hasta, ingresos, costos, bruta, gastos, bruta.subtract(gastos.total()));
    }

    public BalanceGeneral balanceGeneral(Long cia, LocalDate corte) {
        var plan = cuentas.findByCompaniaIdOrderByCodigo(cia);
        var mov = acumular(plan, movimientos(cia, null, corte));
        var activos = seccion(plan, mov, TipoCuenta.ACTIVO, "Activos");
        var pasivos = seccion(plan, mov, TipoCuenta.PASIVO, "Pasivos");
        var capital = seccion(plan, mov, TipoCuenta.CAPITAL, "Capital");
        // Sin asientos de cierre anual: el resultado acumulado de las cuentas de ingreso, costo y gasto se presenta en el capital
        BigDecimal resultado = seccion(plan, mov, TipoCuenta.INGRESO, "").total()
                .subtract(seccion(plan, mov, TipoCuenta.COSTO, "").total())
                .subtract(seccion(plan, mov, TipoCuenta.GASTO, "").total());
        BigDecimal pasivoCapital = pasivos.total().add(capital.total()).add(resultado);
        return new BalanceGeneral(corte, activos, pasivos, capital, resultado, pasivoCapital, activos.total().compareTo(pasivoCapital) == 0);
    }

    public Mayor mayor(Long cia, Long cuentaId, LocalDate desde, LocalDate hasta) {
        rango(desde, hasta);
        var plan = cuentas.findByCompaniaIdOrderByCodigo(cia);
        var cuenta = plan.stream().filter(c -> c.getId().equals(cuentaId)).findFirst().orElseThrow(() -> new NotFoundException("Cuenta contable", cuentaId));
        Long[] ids = descendientes(plan, cuentaId).toArray(Long[]::new);
        BigDecimal saldoAnterior = jdbc.queryForObject("""
                select coalesce(sum(l.debito - l.credito), 0) from asiento_linea l join asiento a on a.id = l.asiento_id
                 where a.compania_id = ? and a.estado = 'REGISTRADO' and a.fecha < ? and l.cuenta_id = any(?)""",
                BigDecimal.class, cia, Date.valueOf(desde), ids);
        BigDecimal[] saldo = {saldoAnterior};
        var lineas = jdbc.query("""
                select a.fecha, a.id, a.numero, a.concepto, l.descripcion, l.debito, l.credito
                  from asiento_linea l join asiento a on a.id = l.asiento_id
                 where a.compania_id = ? and a.estado = 'REGISTRADO' and a.fecha between ? and ? and l.cuenta_id = any(?)
                 order by a.fecha, a.numero, l.linea""",
                (rs, i) -> {
                    saldo[0] = saldo[0].add(rs.getBigDecimal(6)).subtract(rs.getBigDecimal(7));
                    return new LineaMayor(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                            rs.getBigDecimal(6), rs.getBigDecimal(7), saldo[0]);
                }, cia, Date.valueOf(desde), Date.valueOf(hasta), ids);
        return new Mayor(cuenta.getId(), cuenta.getCodigo(), cuenta.getNombre(), cuenta.getTipo(), desde, hasta, saldoAnterior, lineas, saldo[0]);
    }

    // ------------------------------------------------------------------ apoyo
    private Map<Long, Mov> movimientos(Long cia, LocalDate desde, LocalDate hasta) {
        return movimientos(cia, desde, hasta, false);
    }

    private Map<Long, Mov> movimientos(Long cia, LocalDate desde, LocalDate hasta, boolean sinCierreAnual) {
        Map<Long, Mov> m = new HashMap<>();
        jdbc.query("""
                select l.cuenta_id, sum(l.debito), sum(l.credito) from asiento_linea l join asiento a on a.id = l.asiento_id
                 where a.compania_id = ? and a.estado = 'REGISTRADO'
                   and (cast(? as date) is null or a.fecha >= ?) and a.fecha <= ?
                   and (not ? or a.origen_tipo is null or a.origen_tipo <> 'CIERRE_ANUAL')
                 group by l.cuenta_id""",
                rs -> { m.put(rs.getLong(1), new Mov(rs.getBigDecimal(2), rs.getBigDecimal(3))); },
                cia, desde == null ? null : Date.valueOf(desde), desde == null ? null : Date.valueOf(desde), Date.valueOf(hasta), sinCierreAnual);
        return m;
    }

    /** Suma los movimientos de cada cuenta en ella y en todos sus grupos superiores. */
    private static Map<Long, Mov> acumular(List<CuentaContable> plan, Map<Long, Mov> directos) {
        Map<Long, Long> padre = new HashMap<>();
        plan.forEach(c -> padre.put(c.getId(), c.getPadreId()));
        Map<Long, Mov> total = new HashMap<>();
        directos.forEach((id, mov) -> {
            Long actual = id;
            int guardia = 0;
            while (actual != null && guardia++ < 20) {
                total.merge(actual, mov, Mov::mas);
                actual = padre.get(actual);
            }
        });
        return total;
    }

    private static Seccion seccion(List<CuentaContable> plan, Map<Long, Mov> mov, TipoCuenta tipo, String titulo) {
        List<Fila> filas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var c : plan) {
            if (c.getTipo() != tipo) continue;
            Mov m = mov.get(c.getId());
            if (m == null || m.neto().signum() == 0) continue;
            BigDecimal monto = tipo.deudora() ? m.neto() : m.neto().negate();
            filas.add(new Fila(c.getId(), c.getCodigo(), c.getNombre(), c.getNivel(), c.isAceptaMovimiento(), monto));
            if (c.isAceptaMovimiento()) total = total.add(monto);
        }
        return new Seccion(tipo, titulo, filas, total);
    }

    private static List<Long> descendientes(List<CuentaContable> plan, Long raiz) {
        Map<Long, List<Long>> hijos = new HashMap<>();
        plan.forEach(c -> { if (c.getPadreId() != null) hijos.computeIfAbsent(c.getPadreId(), k -> new ArrayList<>()).add(c.getId()); });
        List<Long> res = new ArrayList<>();
        Deque<Long> pila = new ArrayDeque<>(List.of(raiz));
        while (!pila.isEmpty()) {
            Long id = pila.pop();
            res.add(id);
            pila.addAll(hijos.getOrDefault(id, List.of()));
        }
        return res;
    }

    private static void rango(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) throw new BusinessException("RANGO_INVALIDO", "La fecha final es anterior a la inicial");
    }
}
