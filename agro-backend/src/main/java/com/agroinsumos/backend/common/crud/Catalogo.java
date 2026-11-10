package com.agroinsumos.backend.common.crud;

/** Entidad con borrado lógico por estado ('A' activo, 'I' inactivo), como en todo el legacy. */
public interface Catalogo {
    Long getId();
    String getEstado();
    void setEstado(String estado);

    /** Catálogos por compañía devuelven su compania_id; los globales, null. */
    default Long getCompaniaId() { return null; }
}
