package com.agroinsumos.backend.inventario;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.inventario.etiqueta.GeneradorZpl;
import com.agroinsumos.backend.inventario.etiqueta.GeneradorZpl.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneradorZplTest {

    private static Formato f(String ancho, String alto, int dpi) { return new Formato(new BigDecimal(ancho), new BigDecimal(alto), dpi); }

    @Test
    void dimensionesSegunDpi() {
        var d203 = GeneradorZpl.disenar(f("50.8", "25.4", 203), new Contenido("Urea", null, "123456", true));
        assertThat(d203.anchoPuntos()).isEqualTo(406);
        assertThat(d203.altoPuntos()).isEqualTo(203);
        var d600 = GeneradorZpl.disenar(f("50.8", "25.4", 600), new Contenido("Urea", null, "123456", true));
        assertThat(d600.anchoPuntos()).isEqualTo(1219);
        assertThat(GeneradorZpl.zpl(d600, 1)).contains("^PW1219^LL610");
    }

    @Test
    void eanUpcYCode128() {
        var ean = GeneradorZpl.disenar(f("50.8", "25.4", 203), new Contenido("Urea", "RD$2,478.00", "7461234000014", true));
        String zpl = GeneradorZpl.zpl(ean, 3);
        assertThat(zpl).startsWith("^XA^CI28").contains("^BEN,").contains("^FD746123400001^FS").contains("^PQ3,0,1,Y^XZ");
        assertThat(ean.elementos()).anyMatch(e -> e.simbologia() == Simbologia.EAN13);
        // Dígito verificador incorrecto -> Code 128
        var c128 = GeneradorZpl.disenar(f("50.8", "25.4", 203), new Contenido("Urea", null, "7461234000015", true));
        assertThat(GeneradorZpl.zpl(c128, 1)).contains("^BCN,").contains(",N,N,N,A^FD7461234000015");
    }

    @Test
    void textoLargoSeParteYEscapaCaracteresZpl() {
        var d = GeneradorZpl.disenar(f("50.8", "25.4", 203),
                new Contenido("Fertilizante granulado 15-15-15 saco de 50 kg marca Fertilizantes_del_Cibao ^especial^", null, "A-1", false));
        long lineas = d.elementos().stream().filter(e -> "TEXTO".equals(e.tipo())).count();
        assertThat(lineas).isEqualTo(2);
        assertThat(d.elementos().get(1).texto()).endsWith("...");
        String zpl = GeneradorZpl.zpl(GeneradorZpl.disenar(f("101.6", "50.8", 203), new Contenido("A_B ^C~", null, "1", false)), 1);
        assertThat(zpl).contains("^FDA_5FB _5EC_7E^FS");
        // Todo dentro de la etiqueta
        d.elementos().forEach(e -> assertThat(e.y() + e.alto()).isLessThanOrEqualTo(d.altoPuntos()));
    }

    @Test
    void etiquetaDemasiadoPequenaOCodigoLargo() {
        assertThatThrownBy(() -> GeneradorZpl.disenar(f("38.1", "12", 203), new Contenido("Urea", "RD$1.00", "123", true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no cabe");
        assertThatThrownBy(() -> GeneradorZpl.disenar(f("20", "25.4", 203), new Contenido("U", null, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no cabe");
    }
}
