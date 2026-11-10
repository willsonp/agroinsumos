import type { components } from './schema';

/** Esquemas generados desde el OpenAPI del backend (npm run api:gen). */
export type S = components['schemas'];

/** Página de Spring Data (Page<T>). */
export interface Pagina<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Error RFC 9457 (application/problem+json) que devuelve el backend. */
export interface Problema {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  errores?: Record<string, string>;
}
