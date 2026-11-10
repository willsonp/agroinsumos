package com.agroinsumos.backend.common.pago;

public enum FormaPago {
    EFECTIVO, TARJETA, CHEQUE, TRANSFERENCIA,
    /** Crédito del cliente por notas de crédito no aplicadas (no pasa por caja). */
    SALDO_A_FAVOR;

    /**
     * Si el pago se recibe físicamente en la caja del cajero y se cuadra al cierre
     * (efectivo, voucher de tarjeta, cheque). Transferencias y saldo a favor no requieren caja abierta.
     */
    public boolean pasaPorCaja() { return this == EFECTIVO || this == TARJETA || this == CHEQUE; }
}
