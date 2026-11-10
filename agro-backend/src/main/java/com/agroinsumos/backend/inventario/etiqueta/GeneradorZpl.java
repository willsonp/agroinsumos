package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Diseña una etiqueta (nombre, precio, código de barras y su texto) en puntos de la impresora y la convierte a ZPL II.
 * El mismo diseño se devuelve al navegador para la vista previa, así lo que se ve es lo que se imprime.
 * Fuente 0 de Zebra (escalable) con ^CI28 (UTF-8: acentos y ñ).
 */
public final class GeneradorZpl {

    private GeneradorZpl() {}

    public enum Simbologia { CODE128, EAN13, UPCA }

    public record Formato(BigDecimal anchoMm, BigDecimal altoMm, int dpi) {
        public int dpmm() { return switch (dpi) { case 300 -> 12; case 600 -> 24; default -> 8; }; }
        public int ancho() { return anchoMm.multiply(BigDecimal.valueOf(dpmm())).setScale(0, RoundingMode.HALF_UP).intValue(); }
        public int alto() { return altoMm.multiply(BigDecimal.valueOf(dpmm())).setScale(0, RoundingMode.HALF_UP).intValue(); }
    }

    /** Elemento posicionado en puntos. TEXTO: una línea centrada en {@code ancho}. BARRAS: {@code ancho} es el de las barras. */
    public record Elemento(String tipo, int x, int y, int ancho, int alto, String texto, Simbologia simbologia, int modulo) {}

    public record Diseno(int anchoPuntos, int altoPuntos, int puntosPorMm, List<Elemento> elementos) {}

    /** {@code encabezado}: líneas pequeñas al inicio (nombre de la empresa, dirección, teléfono). */
    public record Contenido(List<String> encabezado, String nombre, String precio, String codigo, boolean textoCodigo) {
        public Contenido(String nombre, String precio, String codigo, boolean textoCodigo) {
            this(List.of(), nombre, precio, codigo, textoCodigo);
        }
    }

    /** Ancho medio de un carácter de la fuente 0 respecto a su alto (condensada). */
    private static final double ANCHO_CARACTER = 0.55;

    public static Diseno disenar(Formato f, Contenido c) {
        int dp = f.dpmm(), W = f.ancho(), H = f.alto();
        int margen = Math.round(1.5f * dp), w = W - 2 * margen, gap = Math.max(2, Math.round(0.6f * dp));
        Simbologia sim = simbologia(c.codigo());
        String datos = datosBarras(sim, c.codigo());
        int modulos = modulos(sim, datos);
        // Barras lo más anchas posible (mejor lectura) hasta 0.5 mm por módulo
        int modulo = Math.min(Math.max(1, dp / 2), w / modulos);
        if (modulo < 1) {
            throw new BusinessException("CODIGO_MUY_LARGO", "El código " + c.codigo() + " no cabe en una etiqueta de " + f.anchoMm() + " mm a " + f.dpi() + " dpi");
        }
        int hn = clamp(Math.round(H * 0.13f), Math.round(2.2f * dp), 4 * dp);
        int hp = clamp(Math.round(H * 0.17f), Math.round(2.6f * dp), 6 * dp);
        int ht = clamp(Math.round(H * 0.10f), Math.round(1.8f * dp), 3 * dp);
        int minBarras = 4 * dp;

        for (int maxLineas = 2; maxLineas >= 0; maxLineas--) {
            List<Elemento> el = new ArrayList<>();
            int y = margen;
            int he = clamp(Math.round(H * 0.085f), Math.round(1.6f * dp), Math.round(2.6f * dp));
            int maxEnc = Math.max(4, (int) (w / (he * ANCHO_CARACTER)));
            for (String linea : c.encabezado()) {
                if (linea == null || linea.isBlank()) continue;
                // "dirección · Tel." que no cabe en una línea se parte en dos
                List<String> partes = linea.length() > maxEnc && linea.contains(" · ") ? List.of(linea.split(" · ")) : List.of(linea);
                for (String parte : partes) {
                    el.add(new Elemento("TEXTO", margen, y, w, he, partir(parte, maxEnc, 1).getFirst(), null, 0));
                    y += Math.round(he * 1.12f);
                }
            }
            if (maxLineas > 0 && c.nombre() != null && !c.nombre().isBlank()) {
                for (String linea : partir(c.nombre(), Math.max(4, (int) (w / (hn * ANCHO_CARACTER))), maxLineas)) {
                    el.add(new Elemento("TEXTO", margen, y, w, hn, linea, null, 0));
                    y += Math.round(hn * 1.08f);
                }
            }
            if (c.precio() != null) {
                el.add(new Elemento("TEXTO", margen, y, w, hp, c.precio(), null, 0));
                y += Math.round(hp * 1.08f);
            }
            int abajo = c.textoCodigo() ? ht + gap / 2 : 0;
            int altoBarras = H - margen - abajo - (y + gap);
            if (altoBarras < minBarras && maxLineas > 0) continue;
            if (altoBarras < minBarras) {
                throw new BusinessException("ETIQUETA_PEQUENA", "El contenido no cabe en una etiqueta de " + f.anchoMm() + " × " + f.altoMm() + " mm; quite datos (empresa, precio) o use una más alta");
            }
            int anchoBarras = (modulos - quietos(sim)) * modulo;
            el.add(new Elemento("BARRAS", (W - anchoBarras) / 2, y + gap, anchoBarras, altoBarras, datos, sim, modulo));
            if (c.textoCodigo()) el.add(new Elemento("TEXTO", margen, y + gap + altoBarras + gap / 2, w, ht, c.codigo(), null, 0));
            return new Diseno(W, H, dp, el);
        }
        throw new IllegalStateException("inalcanzable");
    }

    /** ZPL de una etiqueta con {@code copias} (^PQ). */
    public static String zpl(Diseno d, int copias) {
        var sb = new StringBuilder("^XA^CI28^PW").append(d.anchoPuntos()).append("^LL").append(d.altoPuntos()).append("^LH0,0\n");
        for (var e : d.elementos()) {
            if ("TEXTO".equals(e.tipo())) {
                sb.append("^FO").append(e.x()).append(',').append(e.y()).append("^A0N,").append(e.alto()).append(',').append(e.alto())
                  .append("^FB").append(e.ancho()).append(",1,0,C,0^FH^FD").append(escapar(e.texto())).append("^FS\n");
            } else {
                sb.append("^FO").append(e.x()).append(',').append(e.y()).append("^BY").append(e.modulo()).append(",3,").append(e.alto());
                switch (e.simbologia()) {
                    case EAN13 -> sb.append("^BEN,").append(e.alto()).append(",N,N^FD").append(e.texto(), 0, 12);
                    case UPCA -> sb.append("^BUN,").append(e.alto()).append(",N,N,N^FD").append(e.texto(), 0, 11);
                    default -> sb.append("^BCN,").append(e.alto()).append(",N,N,N,A^FD").append(e.texto());
                }
                sb.append("^FS\n");
            }
        }
        return sb.append("^PQ").append(copias).append(",0,1,Y^XZ\n").toString();
    }

    // ------------------------------------------------------------------ apoyo
    static Simbologia simbologia(String codigo) {
        if (codigo.matches("\\d{13}") && digitoEan(codigo.substring(0, 12)) == codigo.charAt(12) - '0') return Simbologia.EAN13;
        if (codigo.matches("\\d{12}") && digitoEan("0" + codigo.substring(0, 11)) == codigo.charAt(11) - '0') return Simbologia.UPCA;
        return Simbologia.CODE128;
    }

    private static String datosBarras(Simbologia s, String codigo) {
        if (s == Simbologia.CODE128 && !codigo.matches("[\\x20-\\x7E]{1,48}")) {
            throw new BusinessException("CODIGO_INVALIDO", "El código '" + codigo + "' tiene caracteres no válidos para Code 128");
        }
        return codigo;
    }

    /** Módulos totales incluyendo zonas silenciosas. Code 128 automático: pares numéricos en el subconjunto C. */
    private static int modulos(Simbologia s, String datos) {
        if (s != Simbologia.CODE128) return 95 + quietos(s);
        int simbolos = 0, i = 0;
        while (i < datos.length()) {
            int j = i;
            while (j < datos.length() && Character.isDigit(datos.charAt(j))) j++;
            int digitos = j - i;
            if (digitos >= 4) { simbolos += digitos / 2 + (digitos % 2) + 1; i = j; } else { simbolos++; i++; }
        }
        return 11 * simbolos + 35 + quietos(s);
    }

    private static int quietos(Simbologia s) { return s == Simbologia.CODE128 ? 20 : 18; }

    private static int digitoEan(String doce) {
        int suma = 0;
        for (int i = 0; i < 12; i++) suma += (doce.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - suma % 10) % 10;
    }

    /** Parte por palabras en líneas de {@code max} caracteres; la última se recorta con "...". */
    static List<String> partir(String texto, int max, int lineas) {
        List<String> res = new ArrayList<>();
        String resto = texto.trim().replaceAll("\\s+", " ");
        while (!resto.isEmpty() && res.size() < lineas) {
            if (resto.length() <= max) { res.add(resto); resto = ""; break; }
            if (res.size() == lineas - 1) { res.add(resto.substring(0, Math.max(1, max - 3)).trim() + "..."); resto = ""; break; }
            int corte = resto.lastIndexOf(' ', max);
            if (corte <= 0) corte = max;
            res.add(resto.substring(0, corte).trim());
            resto = resto.substring(corte).trim();
        }
        return res;
    }

    /** ^FH: _ como indicador hexadecimal; se escapan los caracteres de control de ZPL. */
    static String escapar(String s) {
        return s.replace("_", "_5F").replace("^", "_5E").replace("~", "_7E");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
