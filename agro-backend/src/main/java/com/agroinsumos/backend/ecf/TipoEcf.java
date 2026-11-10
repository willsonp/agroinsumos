package com.agroinsumos.backend.ecf;

import java.util.Arrays;

/** Tipos de Comprobante Fiscal Electrónico (Norma General 06-2018 y Ley 32-23). */
public enum TipoEcf {
    FACTURA_CREDITO_FISCAL(31, "Factura de Crédito Fiscal Electrónica", true),
    FACTURA_CONSUMO(32, "Factura de Consumo Electrónica", true),
    NOTA_DEBITO(33, "Nota de Débito Electrónica", true),
    NOTA_CREDITO(34, "Nota de Crédito Electrónica", true),
    COMPRAS(41, "Comprobante Electrónico de Compras", true),
    GASTOS_MENORES(43, "Comprobante Electrónico para Gastos Menores", true),
    REGIMENES_ESPECIALES(44, "Comprobante Electrónico para Regímenes Especiales", true),
    GUBERNAMENTAL(45, "Comprobante Electrónico Gubernamental", true),
    EXPORTACIONES(46, "Comprobante Electrónico para Exportaciones", false),
    PAGOS_EXTERIOR(47, "Comprobante Electrónico para Pagos al Exterior", false);

    private final int codigo;
    private final String descripcion;
    /** Tipos cuyo XML genera esta versión. Los demás requieren campos adicionales (fase 2). */
    private final boolean soportado;

    TipoEcf(int codigo, String descripcion, boolean soportado) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.soportado = soportado;
    }

    public int codigo() { return codigo; }
    public String descripcion() { return descripcion; }
    public boolean soportado() { return soportado; }

    public boolean requiereRncComprador() {
        // En el e-CF 41 la sección "Comprador" identifica al VENDEDOR informal (cédula obligatoria)
        return this == FACTURA_CREDITO_FISCAL || this == REGIMENES_ESPECIALES || this == GUBERNAMENTAL || this == COMPRAS;
    }

    public boolean esNota() { return this == NOTA_DEBITO || this == NOTA_CREDITO; }

    /**
     * Comprobantes que emite el COMPRADOR (nosotros) por una adquisición: compras a informales (41)
     * y gastos menores (43). No llevan TipoIngresos.
     */
    public boolean esDeAdquisicion() { return this == COMPRAS || this == GASTOS_MENORES || this == PAGOS_EXTERIOR; }

    public static TipoEcf desdeCodigo(int codigo) {
        return Arrays.stream(values()).filter(t -> t.codigo == codigo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tipo e-CF inválido: " + codigo));
    }
}
