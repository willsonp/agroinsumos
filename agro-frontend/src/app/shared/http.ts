import { HttpParams } from '@angular/common/http';

/** HttpParams omitiendo valores vacíos. */
export function params(valores: Record<string, unknown>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(valores)) {
    if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
  }
  return p;
}

/** Fecha local (zona del navegador) en formato ISO yyyy-MM-dd, para filtros. */
export function isoFecha(d: Date | null | undefined): string | null {
  if (!d) return null;
  const z = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${z(d.getMonth() + 1)}-${z(d.getDate())}`;
}

/** Descarga un Blob como archivo. */
export function descargar(blob: Blob, nombre: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Redondeo a 2 decimales, mitad hacia arriba (HALF_UP, como BigDecimal en el backend). Se corrige primero el error
 * binario del producto: 15.25 × 1.18 da 17.994999… en JavaScript y debe redondear a 18.00, no a 17.99.
 */
export const redondear = (n: number) => {
  const centavos = Number((Math.abs(n) * 100).toPrecision(12));
  return (Math.sign(n) * Math.round(centavos)) / 100 || 0;
};
