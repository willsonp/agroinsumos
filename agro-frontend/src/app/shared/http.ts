import { HttpClient, HttpParams } from '@angular/common/http';
import { EMPTY, Observable, expand, reduce } from 'rxjs';

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

/** "2026-11-13" → "13/11/2026" (para textos de reportes y exportaciones). */
export function fechaRd(iso?: string | null): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  return m ? `${m[3]}/${m[2]}/${m[1]}` : '';
}

/**
 * Trae todas las páginas de un listado paginado (para exportar todo lo que cumple el filtro, no solo la página
 * visible). Páginas de 2,000 (el máximo del servidor), hasta `tope` filas.
 */
export function traerTodo<T>(http: HttpClient, url: string, valores: Record<string, unknown>, tope = 50_000): Observable<T[]> {
  const pagina = (page: number) => http.get<{ content: T[]; last: boolean }>(url, { params: params({ ...valores, page, size: 2000 }) });
  return pagina(0).pipe(
    expand((p, i) => (p.last || (i + 1) * 2000 >= tope ? EMPTY : pagina(i + 1))),
    reduce((todo, p) => todo.concat(p.content), [] as T[]),
  );
}
