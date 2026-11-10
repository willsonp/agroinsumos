package com.agroinsumos.backend.contabilidad.fuente;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Utilidades SQL comunes a las fuentes. */
@RequiredArgsConstructor
public abstract class FuenteBase implements FuenteContable {

    protected final JdbcTemplate jdbc;

    /** Condición SQL: el documento (alias) aún no tiene asiento. */
    protected String sinAsiento(String alias) {
        return "not exists (select 1 from asiento a where a.compania_id = " + alias + ".compania_id and a.origen_tipo = '" + origen()
                + "' and a.origen_id = " + alias + ".id and not a.reverso)";
    }

    /** Ids anulados (según {@code condicion} sobre el alias x) con asiento vigente y sin reverso. */
    protected List<Long> anulados(Long cia, String tabla, String condicion) {
        return jdbc.queryForList("select x.id from " + tabla + " x where x.compania_id = ? and (" + condicion + ")"
                + " and exists (select 1 from asiento a where a.compania_id = x.compania_id and a.origen_tipo = ? and a.origen_id = x.id"
                + "             and not a.reverso and a.estado = 'REGISTRADO')"
                + " and not exists (select 1 from asiento a where a.compania_id = x.compania_id and a.origen_tipo = ? and a.origen_id = x.id"
                + "             and a.reverso)", Long.class, cia, origen(), origen());
    }

    protected static Long[] ids(List<DocumentoContable> docs) {
        return docs.stream().map(DocumentoContable::origenId).toArray(Long[]::new);
    }

    protected static <T> Map<Long, List<T>> agrupar(List<T> filas, Function<T, Long> clave) {
        return filas.stream().collect(Collectors.groupingBy(clave));
    }

    /** Reparte {@code total} proporcionalmente a los pesos; la última parte absorbe el redondeo. */
    protected static List<BigDecimal> repartir(BigDecimal total, List<BigDecimal> pesos) {
        List<BigDecimal> partes = new ArrayList<>();
        BigDecimal suma = pesos.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal asignado = BigDecimal.ZERO;
        for (int i = 0; i < pesos.size(); i++) {
            BigDecimal parte;
            if (i == pesos.size() - 1) parte = total.subtract(asignado);
            else if (suma.signum() == 0) parte = BigDecimal.ZERO;
            else parte = total.multiply(pesos.get(i)).divide(suma, 2, RoundingMode.HALF_UP);
            partes.add(parte);
            asignado = asignado.add(parte);
        }
        return partes;
    }

    protected static BigDecimal r2(BigDecimal b) { return b == null ? BigDecimal.ZERO : b.setScale(2, RoundingMode.HALF_UP); }

    protected static String nz(String s) { return s == null ? "" : s; }
}
