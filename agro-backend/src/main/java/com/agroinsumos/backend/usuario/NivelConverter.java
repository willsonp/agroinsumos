package com.agroinsumos.backend.usuario;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NivelConverter implements AttributeConverter<Nivel, String> {
    @Override
    public String convertToDatabaseColumn(Nivel nivel) {
        return nivel == null ? null : String.valueOf(nivel.codigo());
    }

    @Override
    public Nivel convertToEntityAttribute(String db) {
        return db == null || db.isBlank() ? null : Nivel.desdeCodigo(db.charAt(0));
    }
}
