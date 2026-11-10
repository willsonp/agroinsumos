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

export const redondear = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;
