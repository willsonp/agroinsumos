import type { S } from '../api/tipos';
import { redondear } from './http';

type Articulo = S['ArticuloResponse'];
export type TipoPrecio = 'CONTADO' | 'CREDITO' | 'MAYOR';

export interface LineaCarrito {
  articulo: Articulo;
  cantidad: number;
  descuentoPorcentaje: number;
  /** true: cantidad y precio en la presentación del artículo (Saco, Caja); si no, en la unidad base. */
  empaque?: boolean;
}

/** Artículos agregados escaneando el código de barras de su presentación (caja/saco): entran como presentación. */
export const ESCANEADOS_EMPAQUE = new WeakSet<Articulo>();

export function tieneEmpaque(a: Articulo): boolean { return !!a.unidadEmpaqueId && (a.factorEmpaque ?? 0) > 1; }

/** Nombre de la unidad en que va la línea (Libra / Saco). */
export function unidadLinea(l: Pick<LineaCarrito, 'articulo' | 'empaque'>): string {
  return (l.empaque ? l.articulo.unidadEmpaque : l.articulo.unidad) ?? '';
}

export const TASAS: Record<number, number> = { 1: 18, 2: 16, 3: 0, 4: 0 };

/** Precio base (sin ITBIS) según la lista del cliente; si la lista no tiene precio, el de contado. */
export function precioLista(a: Articulo, lista: TipoPrecio, empaque = false): number {
  if (empaque && tieneEmpaque(a)) {
    // Misma regla que el backend: precio propio de la presentación (de la lista o el de contado) o base × factor
    const propio = lista === 'CREDITO' ? a.precioEmpaqueCreditoConItbis : lista === 'MAYOR' ? a.precioEmpaqueMayorConItbis : a.precioEmpaqueContadoConItbis;
    const conItbis = propio && propio > 0 ? propio : a.precioEmpaqueContadoConItbis;
    if (conItbis && conItbis > 0) return conItbis / (1 + TASAS[a.indicadorItbis ?? 4] / 100);
    return precioLista(a, lista) * (a.factorEmpaque ?? 1);
  }
  const p = lista === 'CREDITO' ? a.precioCredito : lista === 'MAYOR' ? a.precioMayor : a.precioContado;
  return p && p > 0 ? p : (a.precioContado ?? 0);
}

export interface Totales { subtotal: number; descuento: number; itbis: number; total: number; }

/**
 * Misma regla que el backend (EcfCalculadora): cada línea gravada parte de su total con ITBIS (cantidad × precio ×
 * (1 + tasa), redondeado), base = total / (1 + tasa) redondeada e ITBIS = total − base. Así RD$500 × 12 = 6,000.00
 * exactos (antes 5,084.75 + 915.26 = 6,000.01). Es una vista previa; el total definitivo lo da el servidor.
 */
export function calcular(lineas: LineaCarrito[], lista: TipoPrecio, exento: boolean): Totales {
  let subtotal = 0, descuento = 0, itbis = 0;
  for (const l of lineas) {
    const precio = precioLista(l.articulo, lista, l.empaque);
    const desc = redondear(l.cantidad * precio * (l.descuentoPorcentaje || 0) / 100);
    const neto = l.cantidad * precio - l.cantidad * precio * (l.descuentoPorcentaje || 0) / 100;
    const tasa = exento ? 0 : TASAS[l.articulo.indicadorItbis ?? 4];
    const f = linea(neto, tasa);
    subtotal += f.base;
    itbis += f.itbis;
    descuento += desc;
  }
  subtotal = redondear(subtotal);
  itbis = redondear(itbis);
  return { subtotal, descuento: redondear(descuento), itbis, total: redondear(subtotal + itbis) };
}

/** Base e ITBIS de una línea desde su total con ITBIS (ver calcular). */
function linea(neto: number, tasa: number): { base: number; itbis: number; total: number } {
  if (tasa <= 0) { const base = redondear(neto); return { base, itbis: 0, total: base }; }
  const f = 1 + tasa / 100;
  const total = redondear(neto * f);
  const base = redondear(total / f);
  return { base, itbis: redondear(total - base), total };
}

/** Importe de la línea con ITBIS (para mostrar en el carrito): el mismo total que usa calcular. */
export function importeConItbis(l: LineaCarrito, lista: TipoPrecio, exento: boolean): number {
  const precio = precioLista(l.articulo, lista, l.empaque);
  const neto = l.cantidad * precio * (1 - (l.descuentoPorcentaje || 0) / 100);
  return linea(neto, exento ? 0 : TASAS[l.articulo.indicadorItbis ?? 4]).total;
}
