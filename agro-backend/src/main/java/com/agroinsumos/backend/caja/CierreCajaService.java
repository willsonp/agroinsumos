package com.agroinsumos.backend.caja;

import com.agroinsumos.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuadre de caja como el sistema anterior: "Totales de entradas y salidas del día" por concepto y forma de pago
 * (monto inicial, facturas a contado, recibos de CxC, apartados, ingresos, salidas) con la cantidad de transacciones,
 * y la distribución de lo contado (monedas por denominación más tarjetas, cheques y depósitos que declara el cajero).
 * Diferencia = total contado − total del día (como el sistema anterior): 0 caja cuadrada; positiva, sobrante;
 * negativa, faltante. Se calcula de lo guardado al cerrar, así que el resultado queda registrado con la caja.
 */
@Service
@RequiredArgsConstructor
public class CierreCajaService {

    /** Montos con signo (las salidas restan), por forma de pago. */
    public record Concepto(String codigo, String descripcion, int cantidad, BigDecimal efectivo, BigDecimal tarjeta,
                           BigDecimal cheque, BigDecimal transferencia, BigDecimal monto) {}

    /** Lo contado al cerrar: efectivo por denominación y lo declarado de las otras formas (o lo del sistema si no se declaró). */
    public record Contado(BigDecimal efectivo, BigDecimal tarjeta, BigDecimal cheque, BigDecimal transferencia, BigDecimal total) {}

    /**
     * @param diferencia total contado − total del día (positiva: sobrante; negativa: faltante); null con la caja abierta
     * @param resultado  "CUADRADA", "SOBRANTE" o "FALTANTE"; null con la caja abierta
     */
    public record CierreResumen(CajaService.SesionDetalle detalle, String usuario, List<Concepto> conceptos,
                                Concepto totalDia, Contado contado, BigDecimal diferencia, String resultado) {}

    private static final Map<String, String> NOMBRES = new LinkedHashMap<>();
    static {
        NOMBRES.put("FACTURAS_CONTADO", "Facturas a contado");
        NOMBRES.put("RECIBOS_CXC", "Recibos de ingresos CxC");
        NOMBRES.put("ABONOS_APARTADOS", "Abonos de apartados");
        NOMBRES.put("INGRESO", "Otros ingresos");
        NOMBRES.put("ANULACION_PAGO", "Anulaciones de pagos");
        NOMBRES.put("EGRESO", "Egresos (gastos)");
        NOMBRES.put("PAGO_PROVEEDOR", "Pagos a suplidores");
        NOMBRES.put("ANULACION_COBRO", "Anulaciones de cobros");
    }

    private final CajaService caja;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public CierreResumen resumen(AuthUser user, Long sesionId) {
        var detalle = caja.detalle(user, sesionId);   // valida compañía y que el cajero solo vea su caja
        Map<String, Concepto> porCodigo = new LinkedHashMap<>();
        // Los recibos del punto de venta (origen VENTA) son facturas a contado; los demás, cobros de CxC
        jdbc.query("""
                with m as (
                  select case
                           when m.tipo = 'COBRO' and m.referencia_tipo = 'RECIBO'
                                and (select r.origen from recibo r where r.id = m.referencia_id) = 'VENTA' then 'FACTURAS_CONTADO'
                           when m.tipo = 'COBRO' and m.referencia_tipo = 'RECIBO' then 'RECIBOS_CXC'
                           when m.tipo = 'COBRO' then 'ABONOS_APARTADOS'
                           else m.tipo end codigo,
                         m.referencia_tipo || ':' || m.referencia_id ref, m.forma_pago forma,
                         case when m.tipo in ('EGRESO', 'PAGO_PROVEEDOR', 'ANULACION_COBRO') then -m.monto else m.monto end monto
                    from caja_movimiento m where m.sesion_id = ?)
                select codigo, count(distinct ref) cantidad,
                       coalesce(sum(monto) filter (where forma = 'EFECTIVO'), 0) efectivo,
                       coalesce(sum(monto) filter (where forma = 'TARJETA'), 0) tarjeta,
                       coalesce(sum(monto) filter (where forma = 'CHEQUE'), 0) cheque,
                       coalesce(sum(monto) filter (where forma = 'TRANSFERENCIA'), 0) transferencia,
                       sum(monto) monto
                  from m group by codigo""", rs -> {
            String c = rs.getString("codigo");
            porCodigo.put(c, new Concepto(c, NOMBRES.getOrDefault(c, c), rs.getInt("cantidad"), rs.getBigDecimal("efectivo"),
                    rs.getBigDecimal("tarjeta"), rs.getBigDecimal("cheque"), rs.getBigDecimal("transferencia"), rs.getBigDecimal("monto")));
        }, sesionId);

        BigDecimal inicial = detalle.sesion().montoInicial();
        List<Concepto> conceptos = new ArrayList<>();
        conceptos.add(new Concepto("MONTO_INICIAL", "Monto inicial en caja", 0, inicial, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, inicial));
        for (String c : NOMBRES.keySet()) {
            var x = porCodigo.get(c);
            if (x == null && !c.equals("FACTURAS_CONTADO") && !c.equals("RECIBOS_CXC")) continue;   // estas dos siempre
            conceptos.add(x != null ? x : new Concepto(c, NOMBRES.get(c), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO));
        }
        Concepto total = conceptos.stream().reduce(new Concepto("TOTAL", "Total del día", 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), (a, b) -> new Concepto("TOTAL", "Total del día",
                a.cantidad() + b.cantidad(), a.efectivo().add(b.efectivo()), a.tarjeta().add(b.tarjeta()), a.cheque().add(b.cheque()),
                a.transferencia().add(b.transferencia()), a.monto().add(b.monto())));

        var s = jdbc.queryForMap("""
                select u.username, s.estado, s.efectivo_contado, s.tarjeta_contado, s.cheque_contado, s.transferencia_contado
                  from caja_sesion s join usuario u on u.id = s.usuario_id where s.id = ?""", sesionId);
        Contado contado = null;
        BigDecimal diferencia = null;
        String resultado = null;
        if ("CERRADA".equals(s.get("estado"))) {
            BigDecimal ef = cero((BigDecimal) s.get("efectivo_contado"));
            BigDecimal tj = declarado(s.get("tarjeta_contado"), total.tarjeta());
            BigDecimal ch = declarado(s.get("cheque_contado"), total.cheque());
            BigDecimal tr = declarado(s.get("transferencia_contado"), total.transferencia());
            contado = new Contado(ef, tj, ch, tr, ef.add(tj).add(ch).add(tr));
            diferencia = contado.total().subtract(total.monto());
            resultado = diferencia.signum() == 0 ? "CUADRADA" : diferencia.signum() > 0 ? "SOBRANTE" : "FALTANTE";
        }
        return new CierreResumen(detalle, (String) s.get("username"), conceptos, total, contado, diferencia, resultado);
    }

    private static BigDecimal cero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal declarado(Object v, BigDecimal sistema) { return v == null ? sistema : (BigDecimal) v; }
}
