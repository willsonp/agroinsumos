package com.agroinsumos.backend.reporte;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Definición de un reporte tabular: filtros que pide, columnas que muestra y la consulta que produce las filas.
 * Agregar un reporte es declarar uno nuevo en un catálogo (Open/Closed); el visor, la impresión y el CSV son comunes.
 */
public record Reporte(String codigo, String titulo, Grupo grupo, String descripcion, String legado, List<Filtro> filtros,
                      List<Columna> columnas, BiFunction<Long, Parametros, List<Map<String, Object>>> consulta) {

    public enum Grupo {
        VENTAS("Ventas", "REP_VENTAS"), CXC_CAJA("Cobros y caja", "REP_CXC_CAJA"),
        INVENTARIO("Inventario", "REP_INVENTARIO"), COMPRAS("Compras", "REP_COMPRAS");
        private final String nombre, permiso;
        Grupo(String nombre, String permiso) { this.nombre = nombre; this.permiso = permiso; }
        public String nombre() { return nombre; }
        public String permiso() { return permiso; }
    }

    /** PROVEEDOR_REQUERIDO: el reporte es de un solo suplidor (p.ej. estado de cuenta). */
    public enum Filtro { RANGO_FECHAS, FECHA_CORTE, CLIENTE, VENDEDOR, DEPARTAMENTO, ALMACEN, PROVEEDOR, PROVEEDOR_REQUERIDO, CON_EXISTENCIA }

    public enum Tipo { TEXTO, ENTERO, NUMERO, MONEDA, PORCENTAJE, FECHA }

    /** {@code clave} = alias de la columna en la consulta. */
    public record Columna(String clave, String titulo, Tipo tipo, boolean totalizar) {
        public static Columna texto(String clave, String titulo) { return new Columna(clave, titulo, Tipo.TEXTO, false); }
        public static Columna fecha(String clave, String titulo) { return new Columna(clave, titulo, Tipo.FECHA, false); }
        public static Columna entero(String clave, String titulo) { return new Columna(clave, titulo, Tipo.ENTERO, true); }
        public static Columna numero(String clave, String titulo) { return new Columna(clave, titulo, Tipo.NUMERO, true); }
        public static Columna moneda(String clave, String titulo) { return new Columna(clave, titulo, Tipo.MONEDA, true); }
        public static Columna monedaSinTotal(String clave, String titulo) { return new Columna(clave, titulo, Tipo.MONEDA, false); }
        public static Columna porcentaje(String clave, String titulo) { return new Columna(clave, titulo, Tipo.PORCENTAJE, false); }
    }

    public record Parametros(LocalDate desde, LocalDate hasta, LocalDate corte, Long clienteId, Long vendedorId, Long departamentoId,
                             Long almacenId, Long proveedorId, boolean conExistencia) {}

    /** Resultado listo para mostrar: totales de las columnas que totalizan (y márgenes recalculados). */
    public record Resultado(String codigo, String titulo, String subtitulo, List<Columna> columnas, List<Map<String, Object>> filas,
                            Map<String, BigDecimal> totales) {}

    public record Info(String codigo, String titulo, Grupo grupo, String grupoNombre, String descripcion, String legado,
                       List<Filtro> filtros, List<Columna> columnas) {}

    public Info info() { return new Info(codigo, titulo, grupo, grupo.nombre(), descripcion, legado, filtros, columnas); }
}
