import { describe, expect, it } from 'vitest';
import { calcular } from './calculo';

const art = (precioContado: number, indicadorItbis = 1, extra = {}) =>
  ({ id: 1, nombre: 'X', precioContado, precioCredito: 0, precioMayor: 0, indicadorItbis, ...extra }) as never;

describe('calcular (debe coincidir con EcfCalculadora del backend)', () => {
  it('precio con ITBIS 1850 convertido a base reproduce el total exacto', () => {
    for (const cantidad of [1, 3, 7]) {
      const t = calcular([{ articulo: art(1567.7966), cantidad, descuentoPorcentaje: 0 }], 'CONTADO', false);
      expect(t.total).toBe(1850 * cantidad);
    }
  });

  it('agrupa el ITBIS por tasa y respeta exentos y descuentos', () => {
    // Igual al caso del backend: 3 x 1950 -5% + servicio 1000 (lista CREDITO)
    const t = calcular([
      { articulo: art(1850, 1, { precioCredito: 1950 }), cantidad: 3, descuentoPorcentaje: 5 },
      { articulo: art(1000, 1), cantidad: 1, descuentoPorcentaje: 0 },
      { articulo: art(400, 4), cantidad: 2.5, descuentoPorcentaje: 0 },
    ], 'CREDITO', false);
    expect(t.subtotal).toBe(5557.5 + 1000 + 1000);
    expect(t.itbis).toBe(1180.35);
    expect(t.descuento).toBe(292.5);
  });

  it('cliente exento no paga ITBIS', () => {
    expect(calcular([{ articulo: art(100), cantidad: 2, descuentoPorcentaje: 0 }], 'CONTADO', true).total).toBe(200);
  });
});
