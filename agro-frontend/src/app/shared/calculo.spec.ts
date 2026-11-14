import { describe, expect, it } from 'vitest';
import { calcular, importeConItbis, precioLista } from './calculo';
import { redondear } from './http';

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

describe('presentaciones (saco/caja)', () => {
  const urea = (extra = {}) => art(30, 1, { unidad: 'Libra', unidadEmpaqueId: 2, unidadEmpaque: 'Saco', factorEmpaque: 100, ...extra });

  it('un saco usa su precio propio (con ITBIS) y las libras el precio por libra', () => {
    const t = calcular([
      { articulo: urea({ precioEmpaqueContadoConItbis: 3245 }), cantidad: 1, descuentoPorcentaje: 0, empaque: true },
      { articulo: urea({ precioEmpaqueContadoConItbis: 3245 }), cantidad: 15.5, descuentoPorcentaje: 0 },
    ], 'CONTADO', false);
    expect(t.subtotal).toBe(2750 + 465);
    expect(t.total).toBe(3793.7);   // igual que la factura del backend
  });

  it('sin precio propio, el saco vale precio por libra × factor', () => {
    expect(precioLista(urea(), 'CONTADO', true)).toBe(3000);
    expect(precioLista(urea(), 'CONTADO', false)).toBe(30);
    // Lista crédito sin precio propio de saco: usa el de contado del saco si existe
    expect(precioLista(urea({ precioEmpaqueContadoConItbis: 3245 }), 'CREDITO', true)).toBeCloseTo(2750, 6);
  });

  it('un artículo sin presentación ignora el indicador de empaque', () => {
    expect(precioLista(art(30), 'CONTADO', true)).toBe(30);
  });
});

describe('redondeo (HALF_UP como el backend)', () => {
  it('corrige el error binario: 15.25 × 1.18 = 17.995 → 18.00', () => {
    expect(redondear(15.25 * 1.18)).toBe(18);
    expect(redondear(1.005)).toBe(1.01);
    expect(redondear(-2.675)).toBe(-2.68);
    expect(redondear(0)).toBe(0);
  });

  it('1.5 lb a RD$12.00 con ITBIS: el importe de la línea coincide con el total', () => {
    const linea = { articulo: art(10.1695), cantidad: 1.5, descuentoPorcentaje: 0 };
    expect(importeConItbis(linea, 'CONTADO', false)).toBe(18);
    expect(calcular([linea], 'CONTADO', false).total).toBe(18);
  });
});

describe('precios con ITBIS incluido (cero/cero)', () => {
  it('caja de 12 a RD$500 con ITBIS = 6,000.00 exactos', () => {
    const glifosato = art(423.7288, 1, { unidadEmpaqueId: 9, factorEmpaque: 12 });
    const linea = { articulo: glifosato, cantidad: 1, descuentoPorcentaje: 0, empaque: true };
    const t = calcular([linea], 'CONTADO', false);
    expect(t.total).toBe(6000);
    expect(t.subtotal).toBe(5084.75);
    expect(t.itbis).toBe(915.25);
    expect(importeConItbis(linea, 'CONTADO', false)).toBe(6000);
    expect(calcular([{ articulo: glifosato, cantidad: 1, descuentoPorcentaje: 0 }], 'CONTADO', false).total).toBe(500);
  });
});
