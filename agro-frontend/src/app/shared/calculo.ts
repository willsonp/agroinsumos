import type { S } from '../api/tipos';
import { redondear } from './http';

type Articulo = S['ArticuloResponse'];
export type TipoPrecio = 'CONTADO' | 'CREDITO' | 'MAYOR';

export interface LineaCarrito {
  articulo: Articulo;
  cantidad: number;
  descuentoPorcentaje: number;
}

export const TASAS: Record<number, number> = { 1: 18, 2: 16, 3: 0, 4: 0 };

/** Precio base (sin ITBIS) según la lista del cliente; si la lista no tiene precio, el de contado. */
export function precioLista(a: Articulo, lista: TipoPrecio): number {
  const p = lista === 'CREDITO' ? a.precioCredito : lista === 'MAYOR' ? a.precioMayor : a.precioContado;
  return p && p > 0 ? p : (a.precioContado ?? 0);
}

export interface Totales { subtotal: number; descuento: number; itbis: number; total: number; }

/**
 * Misma regla que el backend (EcfCalculadora): importe por línea redondeado a 2 decimales,
 * ITBIS calculado sobre la suma de cada tasa. Es una vista previa; el total definitivo lo da el servidor.
 */
export function calcular(lineas: LineaCarrito[], lista: TipoPrecio, exento: boolean): Totales {
  const basePorTasa = new Map<number, number>();
  let subtotal = 0, descuento = 0;
  for (const l of lineas) {
    const precio = precioLista(l.articulo, lista);
    const desc = redondear(l.cantidad * precio * (l.descuentoPorcentaje || 0) / 100);
    const importe = redondear(l.cantidad * precio - desc);
    const tasa = exento ? 0 : TASAS[l.articulo.indicadorItbis ?? 4];
    basePorTasa.set(tasa, (basePorTasa.get(tasa) ?? 0) + importe);
    subtotal += importe;
    descuento += desc;
  }
  let itbis = 0;
  basePorTasa.forEach((base, tasa) => (itbis += redondear(base * tasa / 100)));
  subtotal = redondear(subtotal);
  return { subtotal, descuento: redondear(descuento), itbis: redondear(itbis), total: redondear(subtotal + itbis) };
}

/** Importe de la línea con ITBIS (para mostrar en el carrito). */
export function importeConItbis(l: LineaCarrito, lista: TipoPrecio, exento: boolean): number {
  const precio = precioLista(l.articulo, lista);
  const importe = redondear(l.cantidad * precio * (1 - (l.descuentoPorcentaje || 0) / 100));
  const tasa = exento ? 0 : TASAS[l.articulo.indicadorItbis ?? 4];
  return redondear(importe * (1 + tasa / 100));
}
