package com.agroinsumos.backend.inventario.kardex;

public enum TipoMovimiento {
    SALDO_INICIAL(true), COMPRA(true), VENTA(false), DEVOLUCION_VENTA(true), DEVOLUCION_COMPRA(false),
    AJUSTE_ENTRADA(true), AJUSTE_SALIDA(false), ENTRADA(true), SALIDA(false),
    TRANSFERENCIA_ENTRADA(true), TRANSFERENCIA_SALIDA(false);

    private final boolean entrada;

    TipoMovimiento(boolean entrada) { this.entrada = entrada; }

    public boolean entrada() { return entrada; }
}
