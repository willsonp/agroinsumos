package com.agroinsumos.backend.contabilidad;

/**
 * Cuentas por defecto de los asientos automáticos. Las cuentas de departamento (ingreso, costo, inventario),
 * cliente, proveedor y cuenta bancaria tienen prioridad cuando están indicadas.
 */
public enum ClaveContable {
    CAJA("Caja general (efectivo y cheques recibidos)"),
    BANCO("Banco (cuando la cuenta bancaria no indica su cuenta contable)"),
    TARJETAS_POR_COBRAR("Ventas con tarjeta por cobrar"),
    CXC_CLIENTES("Cuentas por cobrar clientes"),
    CXP_PROVEEDORES("Cuentas por pagar proveedores"),
    INVENTARIO("Inventario de mercancías"),
    INGRESOS_VENTAS("Ingresos por ventas"),
    DEVOLUCIONES_VENTAS("Devoluciones en ventas"),
    COSTO_VENTAS("Costo de ventas"),
    ITBIS_POR_PAGAR("ITBIS facturado por pagar"),
    ITBIS_ADELANTADO("ITBIS pagado en compras (adelantado)"),
    ITBIS_RETENIDO_POR_CLIENTES("ITBIS que nos retuvieron los clientes"),
    ISR_RETENIDO_POR_CLIENTES("ISR que nos retuvieron los clientes"),
    ITBIS_RETENIDO_POR_PAGAR("ITBIS retenido a suplidores por pagar"),
    ISR_RETENIDO_POR_PAGAR("ISR retenido a suplidores por pagar"),
    OTROS_INGRESOS("Otros ingresos (notas de débito a clientes)"),
    INGRESOS_FINANCIEROS("Ingresos financieros (notas de crédito bancarias)"),
    GASTOS_BANCARIOS("Gastos bancarios (notas de débito bancarias)"),
    GASTOS_CAJA("Gastos menores pagados por caja"),
    GASTOS_COMPRAS("Compras de servicios (artículos tipo servicio)");

    private final String descripcion;

    ClaveContable(String descripcion) { this.descripcion = descripcion; }

    public String descripcion() { return descripcion; }
}
