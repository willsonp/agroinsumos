package com.agroinsumos.backend.contabilidad.fuente;

import com.agroinsumos.backend.contabilidad.ClaveContable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Movimientos bancarios propios del módulo Banco (los de pagos y recibos se contabilizan con su documento):
 * depósitos (contrapartida: caja), notas de crédito (ingresos financieros), notas de débito (gastos bancarios),
 * cheques directos (contrapartida obligatoria) y transferencias entre cuentas (banco destino / banco origen).
 */
@Component
public class FuenteMovimientosBancarios extends FuenteBase {

    public FuenteMovimientosBancarios(JdbcTemplate jdbc) { super(jdbc); }

    @Override public String origen() { return "MOV_BANCO"; }
    @Override public String descripcion() { return "Movimientos bancarios"; }

    private static final String PROPIOS = """
            (m.origen_tipo is null or m.origen_tipo in ('MANUAL','DEPOSITO_CAJA')
             or (m.origen_tipo = 'TRANSFERENCIA' and m.tipo = 'TRANSFERENCIA_SALIDA'))""";

    @Override
    public List<DocumentoContable> pendientes(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select m.id, m.numero, m.fecha, m.tipo,
                       -- En pesos: cuentas en otra moneda a su tasa; en transferencias entre monedas, el monto del lado en pesos
                       case when m.tipo = 'TRANSFERENCIA_SALIDA' and cb.moneda <> 'DOP' and dcb.moneda = 'DOP' then par.monto
                            else round(m.monto * m.tasa_cambio, 2) end,
                       m.referencia, m.concepto, m.cuenta_contrapartida, m.origen_tipo,
                       cb.cuenta_contable, cb.nombre, dcb.cuenta_contable, dcb.nombre
                  from movimiento_bancario m join cuenta_bancaria cb on cb.id = m.cuenta_id
                  left join movimiento_bancario par on par.id = m.transferencia_par_id
                  left join cuenta_bancaria dcb on dcb.id = par.cuenta_id
                 where m.compania_id = ? and m.fecha between ? and ? and m.estado = 'REGISTRADO' and %s and %s
                 order by m.fecha, m.id""".formatted(PROPIOS, sinAsiento("m")),
                (rs, i) -> {
                    String tipo = rs.getString(4), contra = rs.getString(8), concepto = rs.getString(7);
                    var monto = rs.getBigDecimal(5);
                    var doc = new DocumentoContable(origen(), rs.getLong(1), rs.getDate(3).toLocalDate(),
                            tipo.replace('_', ' ').toLowerCase() + " " + nz(rs.getString(6)) + " - " + concepto);
                    String ctaBanco = rs.getString(10), origenBanco = "cuenta bancaria " + rs.getString(11);
                    switch (tipo) {
                        case "DEPOSITO" -> {
                            doc.add(Partida.debe(ctaBanco, origenBanco, ClaveContable.BANCO, monto, concepto));
                            boolean caja = "DEPOSITO_CAJA".equals(rs.getString(9));
                            doc.add(Partida.haber(caja ? null : contra, "contrapartida", ClaveContable.CAJA, monto, concepto));
                        }
                        case "NOTA_CREDITO" -> {
                            doc.add(Partida.debe(ctaBanco, origenBanco, ClaveContable.BANCO, monto, concepto));
                            doc.add(Partida.haber(contra, "contrapartida", ClaveContable.INGRESOS_FINANCIEROS, monto, concepto));
                        }
                        case "NOTA_DEBITO" -> {
                            doc.add(Partida.debe(contra, "contrapartida", ClaveContable.GASTOS_BANCARIOS, monto, concepto));
                            doc.add(Partida.haber(ctaBanco, origenBanco, ClaveContable.BANCO, monto, concepto));
                        }
                        case "CHEQUE" -> {
                            // Sin clave por defecto: un cheque directo exige indicar la cuenta de contrapartida
                            doc.add(Partida.debe(contra, "contrapartida del cheque", null, monto, concepto));
                            doc.add(Partida.haber(ctaBanco, origenBanco, ClaveContable.BANCO, monto, concepto));
                        }
                        case "TRANSFERENCIA_SALIDA" -> {
                            doc.add(Partida.debe(rs.getString(12), "cuenta bancaria " + rs.getString(13), ClaveContable.BANCO, monto, concepto));
                            doc.add(Partida.haber(ctaBanco, origenBanco, ClaveContable.BANCO, monto, concepto));
                        }
                        default -> throw new IllegalStateException("Tipo no contemplado: " + tipo);
                    }
                    return doc;
                }, cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Override
    public List<Long> anuladosPorReversar(Long cia) {
        return anulados(cia, "movimiento_bancario", "x.estado = 'ANULADO'");
    }
}
