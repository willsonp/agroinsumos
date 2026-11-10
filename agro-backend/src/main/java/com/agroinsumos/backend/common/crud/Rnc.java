package com.agroinsumos.backend.common.crud;

import com.agroinsumos.backend.common.exception.BusinessException;

/** Normaliza y valida RNC (9 dígitos, dígito verificador DGII) y cédula (11 dígitos). */
public final class Rnc {
    private static final int[] PESOS_RNC = {7, 9, 8, 6, 5, 4, 3, 2};

    private Rnc() {}

    /** Quita guiones/espacios; null si queda vacío. Lanza error si el formato es inválido. */
    public static String normalizar(String valor) {
        if (valor == null) return null;
        String s = valor.replaceAll("[\\s-]", "");
        if (s.isEmpty()) return null;
        if (!s.matches("\\d{9}|\\d{11}")) {
            throw new BusinessException("RNC_INVALIDO", "RNC (9 dígitos) o cédula (11 dígitos) inválido: " + valor);
        }
        if (s.length() == 9 && !rncValido(s)) {
            throw new BusinessException("RNC_INVALIDO", "Dígito verificador de RNC incorrecto: " + valor);
        }
        return s;
    }

    static boolean rncValido(String rnc) {
        int suma = 0;
        for (int i = 0; i < 8; i++) suma += (rnc.charAt(i) - '0') * PESOS_RNC[i];
        int r = suma % 11;
        int digito = r == 0 ? 2 : r == 1 ? 1 : 11 - r;
        return digito == rnc.charAt(8) - '0';
    }
}
