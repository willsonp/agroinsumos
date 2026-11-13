import { describe, expect, it } from 'vitest';
import { montoEnLetras } from './letras';

describe('montoEnLetras', () => {
  it('montos de recibos', () => {
    expect(montoEnLetras(1000)).toBe('MIL PESOS');
    expect(montoEnLetras(1)).toBe('UN PESO');
    expect(montoEnLetras(21)).toBe('VEINTIÚN PESOS');
    expect(montoEnLetras(100)).toBe('CIEN PESOS');
    expect(montoEnLetras(101)).toBe('CIENTO UN PESOS');
    expect(montoEnLetras(525)).toBe('QUINIENTOS VEINTICINCO PESOS');
    expect(montoEnLetras(1525.5)).toBe('MIL QUINIENTOS VEINTICINCO PESOS CON 50/100');
    expect(montoEnLetras(31000)).toBe('TREINTA Y UN MIL PESOS');
    expect(montoEnLetras(767)).toBe('SETECIENTOS SESENTA Y SIETE PESOS');
    expect(montoEnLetras(1_000_000)).toBe('UN MILLÓN DE PESOS');
    expect(montoEnLetras(2_350_000.05)).toBe('DOS MILLONES TRESCIENTOS CINCUENTA MIL PESOS CON 05/100');
    expect(montoEnLetras(0.75)).toBe('CERO PESOS CON 75/100');
  });
});
