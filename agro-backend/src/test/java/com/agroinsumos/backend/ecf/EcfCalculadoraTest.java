package com.agroinsumos.backend.ecf;

import com.agroinsumos.backend.ecf.dto.EcfDtos.Item;
import com.agroinsumos.backend.ecf.emision.EcfCalculadora;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Totales con precios escritos con ITBIS: el cliente paga el precio exacto y base + ITBIS cuadran al centavo. */
class EcfCalculadoraTest {

    /** RD$500 con ITBIS → 423.7288 sin ITBIS (como lo guarda el artículo). */
    private static final BigDecimal BASE_500 = new BigDecimal("423.7288");

    private static Item item(String cantidad, BigDecimal precio, int indicador) {
        return new Item("X", 1, new BigDecimal(cantidad), precio, null, indicador);
    }

    @Test
    void cajaDe12AQuinientosConItbisDaSeisMilExactos() {
        var t = EcfCalculadora.calcular(List.of(item("12", BASE_500, 1)));
        assertThat(t.montoTotal()).isEqualByComparingTo("6000.00");        // antes 6,000.01
        assertThat(t.montoGravadoI1()).isEqualByComparingTo("5084.75");
        assertThat(t.totalItbis1()).isEqualByComparingTo("915.25");
        assertThat(t.lineas().getFirst().itbis()).isEqualByComparingTo("915.25");
    }

    @Test
    void unaUnidadYVariasLineasCuadranConLaSumaDeLineas() {
        var t = EcfCalculadora.calcular(List.of(item("1", BASE_500, 1), item("12", BASE_500, 1), item("1.5", new BigDecimal("10.1695"), 1),
                item("3", new BigDecimal("250"), 4)));
        // 500 + 6000 + 18.00 (1.5 lb a RD$12 con ITBIS) + 750 exento
        assertThat(t.montoTotal()).isEqualByComparingTo("7268.00");
        BigDecimal sumaItbisLineas = t.lineas().stream().map(EcfCalculadora.Linea::itbis).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumaItbisLineas).isEqualByComparingTo(t.totalItbis());
        BigDecimal sumaMontos = t.lineas().stream().map(EcfCalculadora.Linea::montoItem).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumaMontos.add(t.totalItbis())).isEqualByComparingTo(t.montoTotal());
        // Cada línea se aparta de precio × cantidad menos de un centavo (tolerancia de redondeo del e-CF)
        for (var l : t.lineas()) {
            BigDecimal exacto = l.item().cantidad().multiply(l.item().precioUnitario());
            assertThat(l.montoItem().subtract(exacto).abs()).isLessThan(new BigDecimal("0.01"));
        }
    }

    @Test
    void preciosDeDosDecimalesDanLoMismoQueAntes() {
        // 2 x 2100 = 4200 + 18% = 756 (redondeando la base y aplicando la tasa, como antes)
        var t = EcfCalculadora.calcular(List.of(item("2", new BigDecimal("2100"), 1), item("1", new BigDecimal("1000"), 2)));
        assertThat(t.totalItbis1()).isEqualByComparingTo("756.00");
        assertThat(t.totalItbis2()).isEqualByComparingTo("160.00");
        assertThat(t.montoTotal()).isEqualByComparingTo("6116.00");
    }
}
