package com.agroinsumos.backend.contabilidad;

/** Grupos del catálogo. Activo, costo y gasto son de naturaleza deudora; pasivo, capital e ingreso, acreedora. */
public enum TipoCuenta {
    ACTIVO, PASIVO, CAPITAL, INGRESO, COSTO, GASTO;

    public boolean deudora() { return this == ACTIVO || this == COSTO || this == GASTO; }

    /** Cuentas de resultado (estado de resultados). */
    public boolean resultado() { return this == INGRESO || this == COSTO || this == GASTO; }
}
