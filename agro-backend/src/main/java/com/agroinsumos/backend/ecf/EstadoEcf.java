package com.agroinsumos.backend.ecf;

public enum EstadoEcf {
    /** Firmado y guardado; pendiente de envío (también estado de contingencia si la DGII no responde). */
    FIRMADO,
    ENVIADO,
    EN_PROCESO,
    ACEPTADO,
    ACEPTADO_CONDICIONAL,
    RECHAZADO,
    ERROR_ENVIO;

    public boolean pendienteDeEnvio() { return this == FIRMADO || this == ERROR_ENVIO; }

    public boolean pendienteDeResultado() { return this == ENVIADO || this == EN_PROCESO; }

    public boolean finalizado() { return this == ACEPTADO || this == ACEPTADO_CONDICIONAL || this == RECHAZADO; }
}
