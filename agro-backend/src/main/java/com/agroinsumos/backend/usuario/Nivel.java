package com.agroinsumos.backend.usuario;

import java.util.Arrays;

/** Nivel de acceso legacy (columna usuario.nivel) mapeado a roles de Spring Security. */
public enum Nivel {
    ADMIN('A'), SUPERVISOR('S'), USUARIO('U'), OTRO('O');

    private final char codigo;

    Nivel(char codigo) { this.codigo = codigo; }

    public char codigo() { return codigo; }

    public String rol() { return name(); }

    public static Nivel desdeCodigo(char c) {
        return Arrays.stream(values()).filter(n -> n.codigo == c).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nivel desconocido: " + c));
    }
}
