package com.agroinsumos.backend.banco;

public enum TipoMovimientoBancario {
    DEPOSITO(true), CHEQUE(false), TRANSFERENCIA_ENTRADA(true), TRANSFERENCIA_SALIDA(false),
    /** Cargo del banco: comisiones, impuesto 0.15 %, etc. */
    NOTA_DEBITO(false),
    /** Abono del banco: intereses, reversos, etc. */
    NOTA_CREDITO(true);

    private final boolean entrada;

    TipoMovimientoBancario(boolean entrada) { this.entrada = entrada; }

    public boolean entrada() { return entrada; }
}
