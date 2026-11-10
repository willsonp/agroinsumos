package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Diferencia del arqueo al cerrar una caja (efectivo contado − esperado), en la fecha del cierre:
 * sobrante: Dr Caja / Cr Sobrantes de caja; faltante: Dr Faltantes de caja / Cr Caja.
 */
@Component
public class FuenteDiferenciasCaja extends FuenteBase {

    public FuenteDiferenciasCaja(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "CIERRE_CAJA"; }
    @Override public String descripcion() { return "Sobrantes y faltantes de caja"; }

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select s.id, s.numero, (s.cerrada_en at time zone 'America/Santo_Domingo')::date, s.diferencia, u.username
                  from caja_sesion s join usuario u on u.id = s.usuario_id
                 where s.compania_id = ? and s.estado = 'CERRADA' and coalesce(s.diferencia, 0) <> 0
                   and (s.cerrada_en at time zone 'America/Santo_Domingo')::date between ? and ? and %s
                 order by s.cerrada_en""".formatted(sinAsiento("s")),
                (rs, i) -> {
                    BigDecimal dif = rs.getBigDecimal(4);
                    boolean sobrante = dif.signum() > 0;
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            (sobrante ? "Sobrante" : "Faltante") + " de caja #" + rs.getLong(2) + " (" + rs.getString(5) + ")");
                    BigDecimal monto = dif.abs();
                    if (sobrante) {
                        doc.add(Partida.debe(ClaveContable.CAJA, monto, "Sobrante en el arqueo"));
                        doc.add(Partida.haber(ClaveContable.SOBRANTES_CAJA, monto, "Sobrante de caja"));
                    } else {
                        doc.add(Partida.debe(ClaveContable.FALTANTES_CAJA, monto, "Faltante de caja"));
                        doc.add(Partida.haber(ClaveContable.CAJA, monto, "Faltante en el arqueo"));
                    }
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) { return List.of(); }
}
