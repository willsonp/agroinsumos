package com.agroinsumos.backend.reporte;

import com.agroinsumos.backend.reporte.Reporte.Columna;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.agroinsumos.backend.reporte.Reporte.Filtro.*;
import static com.agroinsumos.backend.reporte.Reporte.Grupo.INVENTARIO;

/** Inventario valorizado, lista de precios, faltantes y artículos por suplidor. Precios de venta con ITBIS. */
@Component
class ReportesInventario extends CatalogoBase {

    ReportesInventario(NamedParameterJdbcTemplate jdbc) { super(jdbc); }

    private static final String EXISTENCIA = """
            (select x.articulo_id, sum(x.cantidad) cantidad from existencia x
              where (:almacen is null or x.almacen_id = :almacen) group by x.articulo_id)""";

    @Override
    public List<Reporte> reportes() {
        return List.of(
                reporte("inventario-valorizado", "Inventario valorizado", INVENTARIO,
                        "Existencia de cada artículo a costo promedio y a precio de venta de contado (con ITBIS).",
                        "RptListadoArticuloInventarioXCosto", List.of(ALMACEN, DEPARTAMENTO, CON_EXISTENCIA),
                        List.of(Columna.texto("articulo", "Artículo"), Columna.texto("codigo", "Código"), Columna.texto("departamento", "Departamento"),
                                Columna.texto("unidad", "Unidad"), Columna.numero("existencia", "Existencia"),
                                Columna.monedaSinTotal("costo", "Costo promedio"), Columna.moneda("valor_costo", "Valor al costo"),
                                Columna.monedaSinTotal("precio", "Precio contado"), Columna.moneda("valor_venta", "Valor a precio")),
                        """
                        select a.nombre articulo, coalesce(a.codigo_barra, a.referencia) codigo, d.nombre departamento, u.nombre unidad,
                               coalesce(e.cantidad, 0) existencia, round(a.costo_promedio, 2) costo,
                               round(coalesce(e.cantidad, 0) * a.costo_promedio, 2) valor_costo,
                               round(a.precio_contado * (1 + %s / 100.0), 2) precio,
                               round(coalesce(e.cantidad, 0) * a.precio_contado * (1 + %s / 100.0), 2) valor_venta
                          from articulo a join departamento d on d.id = a.departamento_id join unidad u on u.id = a.unidad_id
                          left join %s e on e.articulo_id = a.id
                         where a.compania_id = :cia and a.tipo = 'B' and a.estado = 'A'
                           and (:departamento is null or a.departamento_id = :departamento)
                           and (not :conExistencia or coalesce(e.cantidad, 0) <> 0)
                         order by d.nombre, a.nombre""".formatted(TASA_ITBIS, TASA_ITBIS, EXISTENCIA)),

                reporte("lista-precios", "Lista de precios", INVENTARIO,
                        "Precios de venta con ITBIS por lista (si una lista no tiene precio se usa el de contado).", "RptListadoPrecioArticulos",
                        List.of(DEPARTAMENTO, CON_EXISTENCIA),
                        List.of(Columna.texto("articulo", "Artículo"), Columna.texto("codigo", "Código"), Columna.texto("departamento", "Departamento"),
                                Columna.texto("unidad", "Unidad"), Columna.monedaSinTotal("contado", "Contado"), Columna.monedaSinTotal("credito", "Crédito"),
                                Columna.monedaSinTotal("mayor", "Por mayor"), Columna.texto("itbis", "ITBIS")),
                        """
                        select a.nombre articulo, coalesce(a.codigo_barra, a.referencia) codigo, d.nombre departamento, u.nombre unidad,
                               round(a.precio_contado * (1 + %1$s / 100.0), 2) contado,
                               round((case when a.precio_credito > 0 then a.precio_credito else a.precio_contado end) * (1 + %1$s / 100.0), 2) credito,
                               round((case when a.precio_mayor > 0 then a.precio_mayor else a.precio_contado end) * (1 + %1$s / 100.0), 2) mayor,
                               case a.indicador_itbis when 4 then 'Exento' else %1$s || '%%' end itbis
                          from articulo a join departamento d on d.id = a.departamento_id join unidad u on u.id = a.unidad_id
                          left join %2$s e on e.articulo_id = a.id
                         where a.compania_id = :cia and a.estado = 'A' and (:departamento is null or a.departamento_id = :departamento)
                           and (not :conExistencia or coalesce(e.cantidad, 0) <> 0)
                         order by d.nombre, a.nombre""".formatted(TASA_ITBIS, EXISTENCIA)),

                reporte("bajo-minimo", "Artículos bajo el mínimo (reposición)", INVENTARIO,
                        "Artículos con existencia igual o menor a su stock mínimo, con su suplidor habitual.", "RptArticulo",
                        List.of(ALMACEN, DEPARTAMENTO),
                        List.of(Columna.texto("articulo", "Artículo"), Columna.texto("departamento", "Departamento"),
                                Columna.numero("existencia", "Existencia"), Columna.numero("minimo", "Mínimo"), Columna.numero("faltante", "Por reponer"),
                                Columna.texto("suplidor", "Suplidor habitual"), Columna.monedaSinTotal("ultimo_costo", "Último costo")),
                        """
                        select a.nombre articulo, d.nombre departamento, coalesce(e.cantidad, 0) existencia, a.stock_minimo minimo,
                               a.stock_minimo - coalesce(e.cantidad, 0) faltante, p.nombre suplidor, a.costo ultimo_costo
                          from articulo a join departamento d on d.id = a.departamento_id left join proveedor p on p.id = a.proveedor_id
                          left join %s e on e.articulo_id = a.id
                         where a.compania_id = :cia and a.tipo = 'B' and a.estado = 'A' and a.stock_minimo > 0
                           and coalesce(e.cantidad, 0) <= a.stock_minimo and (:departamento is null or a.departamento_id = :departamento)
                         order by faltante desc""".formatted(EXISTENCIA)),

                reporte("articulos-por-suplidor", "Artículos comprados por suplidor", INVENTARIO,
                        "Lo comprado a cada suplidor en el período: cantidad, costo promedio de compra, último costo y última compra.",
                        "RptArticulosPorSuplidor", List.of(RANGO_FECHAS, PROVEEDOR, DEPARTAMENTO),
                        List.of(Columna.texto("suplidor", "Suplidor"), Columna.texto("articulo", "Artículo"), Columna.entero("compras", "Compras"),
                                Columna.numero("cantidad", "Cantidad"), Columna.moneda("importe", "Importe"),
                                Columna.monedaSinTotal("costo_promedio", "Costo prom."), Columna.monedaSinTotal("ultimo_costo", "Último costo"),
                                Columna.fecha("ultima", "Última compra")),
                        """
                        select p.nombre suplidor, a.nombre articulo, count(distinct c.id) compras, sum(cl.cantidad) cantidad, sum(cl.importe) importe,
                               round(sum(cl.importe) / nullif(sum(cl.cantidad), 0), 2) costo_promedio,
                               round((array_agg(cl.costo_unitario order by c.fecha desc, c.id desc))[1], 2) ultimo_costo, max(c.fecha) ultima
                          from compra_linea cl join compra c on c.id = cl.compra_id join proveedor p on p.id = c.proveedor_id
                          join articulo a on a.id = cl.articulo_id
                         where c.compania_id = :cia and c.estado <> 'ANULADA' and c.fecha between :desde and :hasta
                           and (:proveedor is null or c.proveedor_id = :proveedor) and (:departamento is null or a.departamento_id = :departamento)
                         group by p.id, p.nombre, a.id, a.nombre order by p.nombre, a.nombre"""));
    }
}
