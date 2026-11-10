package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas de Cuentas por Cobrar. Reemplaza los reportes que llenaban tablas temporales
 * (tmpestadocxc, tmpantiguedad, tmpbalancecxc...) con consultas directas.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstadoCuentaService {

    public record DocumentoPendiente(String tipo, Long id, long numero, String comprobante, LocalDate fecha,
                                     LocalDate vencimiento, BigDecimal total, BigDecimal balance, int diasVencido) {}

    public record Movimiento(LocalDate fecha, String tipo, Long documentoId, long numero, String referencia,
                             BigDecimal debito, BigDecimal credito, BigDecimal balance) {}

    public record EstadoCuenta(Long clienteId, String cliente, LocalDate desde, LocalDate hasta,
                               BigDecimal saldoInicial, List<Movimiento> movimientos, BigDecimal saldoFinal,
                               BigDecimal saldoAFavor) {}

    public record Antiguedad(Long clienteId, String cliente, BigDecimal corriente, BigDecimal d1a30, BigDecimal d31a60,
                             BigDecimal d61a90, BigDecimal mas90, BigDecimal total) {}

    private final JdbcTemplate jdbc;
    private final ClienteService clientes;
    private final Clock clock;

    /** Movimientos que afectan el balance del cliente (débitos +, créditos -). */
    private static final String MOVIMIENTOS = """
            select f.fecha, 'FACTURA' tipo, f.id, f.numero, coalesce(f.encf, f.ncf_legacy) ref, f.total debito, 0 credito
              from factura f
             where f.cliente_id = ? and (f.estado <> 'ANULADA' or exists (select 1 from devolucion d where d.factura_id = f.id))
            union all
            select n.fecha, 'NOTA_DEBITO', n.id, n.numero, n.encf, n.total, 0 from nota_debito n where n.cliente_id = ?
            union all
            select r.fecha, 'RECIBO', r.id, r.numero, r.concepto, 0, r.total_aplicado + r.total_retenciones
              from recibo r where r.cliente_id = ? and r.estado = 'EMITIDO'
            union all
            select d.fecha, 'NOTA_CREDITO', d.id, d.numero, d.encf, 0, d.aplicado_a_balance
              from devolucion d join factura f on f.id = d.factura_id where f.cliente_id = ? and d.aplicado_a_balance > 0
            """;

    public EstadoCuenta estadoCuenta(Long cia, Long clienteId, LocalDate desde, LocalDate hasta) {
        var c = clientes.cargar(cia, clienteId);
        LocalDate d = desde != null ? desde : LocalDate.now(clock).withDayOfYear(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now(clock);
        Object[] ids = {clienteId, clienteId, clienteId, clienteId};
        BigDecimal saldo = jdbc.queryForObject("select coalesce(sum(debito - credito), 0) from (" + MOVIMIENTOS
                + ") m where m.fecha < ?", BigDecimal.class, concat(ids, Date.valueOf(d)));
        BigDecimal inicial = saldo;
        List<Movimiento> lista = new ArrayList<>();
        var filas = jdbc.queryForList("select * from (" + MOVIMIENTOS + ") m where m.fecha between ? and ? "
                + "order by m.fecha, case m.tipo when 'FACTURA' then 0 when 'NOTA_DEBITO' then 1 else 2 end, m.id",
                concat(ids, Date.valueOf(d), Date.valueOf(h)));
        for (var f : filas) {
            BigDecimal deb = (BigDecimal) f.get("debito"), cred = (BigDecimal) f.get("credito");
            saldo = saldo.add(deb).subtract(cred);
            lista.add(new Movimiento(((Date) f.get("fecha")).toLocalDate(), (String) f.get("tipo"),
                    ((Number) f.get("id")).longValue(), ((Number) f.get("numero")).longValue(), (String) f.get("ref"),
                    deb, cred, saldo));
        }
        return new EstadoCuenta(clienteId, c.getNombre(), d, h, inicial, lista, saldo, c.getSaldoAFavor());
    }

    public List<DocumentoPendiente> pendientes(Long cia, Long clienteId) {
        clientes.cargar(cia, clienteId);
        return jdbc.query("""
                select * from (
                  select 'FACTURA' tipo, id, numero, coalesce(encf, ncf_legacy) comp, fecha,
                         coalesce(fecha_vencimiento, fecha) venc, total, balance
                    from factura where cliente_id = ? and balance > 0 and estado <> 'ANULADA'
                  union all
                  select 'NOTA_DEBITO', id, numero, encf, fecha, fecha_vencimiento, total, balance
                    from nota_debito where cliente_id = ? and balance > 0) x
                order by venc, id
                """, (rs, i) -> {
            LocalDate venc = rs.getDate("venc").toLocalDate();
            return new DocumentoPendiente(rs.getString("tipo"), rs.getLong("id"), rs.getLong("numero"), rs.getString("comp"),
                    rs.getDate("fecha").toLocalDate(), venc, rs.getBigDecimal("total"), rs.getBigDecimal("balance"),
                    (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(venc, LocalDate.now(clock))));
        }, clienteId, clienteId);
    }

    /** Antigüedad de saldos por días vencidos (legacy Balance_Antiguedad + tmpantiguedad). */
    public List<Antiguedad> antiguedad(Long cia) {
        return jdbc.query("""
                with docs as (
                  select cliente_id, balance, ? - coalesce(fecha_vencimiento, fecha) dias
                    from factura where compania_id = ? and balance > 0 and estado <> 'ANULADA'
                  union all
                  select cliente_id, balance, ? - fecha_vencimiento from nota_debito where compania_id = ? and balance > 0)
                select c.id, c.nombre,
                       sum(balance) filter (where dias <= 0) corriente,
                       sum(balance) filter (where dias between 1 and 30) d30,
                       sum(balance) filter (where dias between 31 and 60) d60,
                       sum(balance) filter (where dias between 61 and 90) d90,
                       sum(balance) filter (where dias > 90) m90,
                       sum(balance) total
                  from docs join cliente c on c.id = docs.cliente_id
                 group by c.id, c.nombre order by total desc
                """, (rs, i) -> new Antiguedad(rs.getLong(1), rs.getString(2), z(rs.getBigDecimal(3)), z(rs.getBigDecimal(4)),
                z(rs.getBigDecimal(5)), z(rs.getBigDecimal(6)), z(rs.getBigDecimal(7)), z(rs.getBigDecimal(8))),
                Date.valueOf(LocalDate.now(clock)), cia, Date.valueOf(LocalDate.now(clock)), cia);
    }

    private static BigDecimal z(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
