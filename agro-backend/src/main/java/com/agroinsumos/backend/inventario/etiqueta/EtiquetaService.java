package com.agroinsumos.backend.inventario.etiqueta;

import com.agroinsumos.backend.catalogo.cliente.TipoPrecio;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.inventario.articulo.Articulo;
import com.agroinsumos.backend.inventario.articulo.ArticuloService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Etiquetas de artículos en ZPL: diseño, vista previa, archivo .zpl y envío a impresora de red. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EtiquetaService {

    public record Tamano(String nombre, BigDecimal anchoMm, BigDecimal altoMm) {}

    /** Tamaños habituales de etiquetas térmicas (pulgadas → mm). */
    public static final List<Tamano> TAMANOS = List.of(
            new Tamano("1.5 × 1 pulg (38 × 25 mm)", new BigDecimal("38.1"), new BigDecimal("25.4")),
            new Tamano("2 × 1 pulg (51 × 25 mm)", new BigDecimal("50.8"), new BigDecimal("25.4")),
            new Tamano("2.25 × 1.25 pulg (57 × 32 mm)", new BigDecimal("57.2"), new BigDecimal("31.8")),
            new Tamano("3 × 2 pulg (76 × 51 mm)", new BigDecimal("76.2"), new BigDecimal("50.8")),
            new Tamano("4 × 2 pulg (102 × 51 mm)", new BigDecimal("101.6"), new BigDecimal("50.8")),
            new Tamano("4 × 6 pulg (102 × 152 mm)", new BigDecimal("101.6"), new BigDecimal("152.4")));

    public record Item(@NotNull Long articuloId, @NotNull @Min(1) @Max(1000) Integer cantidad) {}

    public record Request(@NotNull @DecimalMin("15") @DecimalMax("120") BigDecimal anchoMm,
                          @NotNull @DecimalMin("10") @DecimalMax("200") BigDecimal altoMm,
                          @NotNull Integer dpi,
                          /** Lista de precio a imprimir (con ITBIS); vacío = sin precio (por defecto: los precios cambian). */
                          TipoPrecio precio,
                          Boolean mostrarNombre, Boolean mostrarCodigo,
                          /** Encabezado con los datos de la empresa. */
                          Boolean mostrarEmpresa, Boolean mostrarDireccion, Boolean mostrarTelefono,
                          @NotEmpty @Size(max = 200) List<@Valid Item> items) {}

    public record Etiqueta(Long articuloId, String nombre, String codigo, GeneradorZpl.Simbologia simbologia, String precio,
                           int cantidad, GeneradorZpl.Diseno diseno) {}

    public record Response(int totalEtiquetas, String zpl, List<Etiqueta> etiquetas) {}

    public record Impresion(int totalEtiquetas, String impresora, int bytes) {}

    private final ArticuloService articulos;
    private final ImpresoraEtiquetaService impresoras;
    private final com.agroinsumos.backend.compania.CompaniaService companias;

    public Response generar(Long cia, Request r) {
        if (r.dpi() != 203 && r.dpi() != 300 && r.dpi() != 600) throw new BusinessException("DPI_INVALIDO", "Resolución: 203, 300 o 600 dpi");
        int total = r.items().stream().mapToInt(Item::cantidad).sum();
        if (total > 5000) throw new BusinessException("DEMASIADAS_ETIQUETAS", "Máximo 5,000 etiquetas por trabajo");
        var formato = new GeneradorZpl.Formato(r.anchoMm(), r.altoMm(), r.dpi());
        List<String> encabezado = encabezado(cia, r);
        var zpl = new StringBuilder();
        List<Etiqueta> res = new ArrayList<>();
        for (var it : r.items()) {
            Articulo a = articulos.cargar(cia, it.articuloId());
            String codigo = codigo(a);
            String precio = r.precio() == null ? null : moneda(a.precio(r.precio())
                    .multiply(BigDecimal.ONE.add(a.tasaItbis().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP));
            var diseno = GeneradorZpl.disenar(formato, new GeneradorZpl.Contenido(encabezado,
                    Boolean.FALSE.equals(r.mostrarNombre()) ? null : a.getNombre(), precio, codigo, !Boolean.FALSE.equals(r.mostrarCodigo())));
            zpl.append(GeneradorZpl.zpl(diseno, it.cantidad()));
            res.add(new Etiqueta(a.getId(), a.getNombre(), codigo, GeneradorZpl.simbologia(codigo), precio, it.cantidad(), diseno));
        }
        return new Response(total, zpl.toString(), res);
    }

    /** Envía el ZPL por TCP (RAW) a la impresora registrada. */
    public Impresion imprimir(Long cia, Long impresoraId, Request r) {
        var imp = impresoras.cargarActiva(cia, impresoraId);
        var gen = generar(cia, r);
        byte[] bytes = gen.zpl().getBytes(StandardCharsets.UTF_8);
        try (var s = new Socket()) {
            s.connect(new InetSocketAddress(imp.getHost(), imp.getPuerto()), 4000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new BusinessException("IMPRESORA_NO_RESPONDE", "No se pudo enviar a " + imp.getNombre() + " (" + imp.getHost() + ":"
                    + imp.getPuerto() + "): " + e.getMessage());
        }
        return new Impresion(gen.totalEtiquetas(), imp.getNombre(), bytes.length);
    }

    /** Nombre comercial (o razón social) en una línea; dirección y teléfono juntos en otra. */
    private List<String> encabezado(Long cia, Request r) {
        boolean empresa = Boolean.TRUE.equals(r.mostrarEmpresa()), dir = Boolean.TRUE.equals(r.mostrarDireccion()),
                tel = Boolean.TRUE.equals(r.mostrarTelefono());
        if (!empresa && !dir && !tel) return List.of();
        var c = companias.cargar(cia);
        List<String> res = new ArrayList<>();
        if (empresa) res.add(c.getNombreComercial() != null ? c.getNombreComercial() : c.getNombre());
        List<String> detalle = new ArrayList<>();
        if (dir && c.getDireccion() != null) detalle.add(c.getDireccion());
        if (tel && c.getTelefono() != null) detalle.add("Tel. " + c.getTelefono());
        if (!detalle.isEmpty()) res.add(String.join(" · ", detalle));
        return res;
    }

    /** Código de barras del artículo: código de barras, si no la referencia, si no el número interno. */
    static String codigo(Articulo a) {
        if (a.getCodigoBarra() != null && !a.getCodigoBarra().isBlank()) return a.getCodigoBarra().trim();
        if (a.getReferencia() != null && a.getReferencia().trim().matches("[\\x20-\\x7E]+")) return a.getReferencia().trim();
        return String.format("%06d", a.getId());
    }

    private static String moneda(BigDecimal v) {
        return "RD$" + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }
}
