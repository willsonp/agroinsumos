import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import type { S } from '../../api/tipos';

export type Cuenta = S['CuentaContableResponse'];

export const TIPOS_CUENTA: { valor: Cuenta['tipo']; texto: string }[] = [
  { valor: 'ACTIVO', texto: 'Activo' }, { valor: 'PASIVO', texto: 'Pasivo' }, { valor: 'CAPITAL', texto: 'Capital' },
  { valor: 'INGRESO', texto: 'Ingreso' }, { valor: 'COSTO', texto: 'Costo' }, { valor: 'GASTO', texto: 'Gasto' },
];

/** Catálogo de cuentas en memoria (para selectores y reportes). */
@Injectable({ providedIn: 'root' })
export class CuentasContables {
  private readonly http = inject(HttpClient);
  readonly todas = signal<Cuenta[]>([]);

  cargar(): void {
    this.http.get<Cuenta[]>('/api/v1/contabilidad/cuentas/arbol').subscribe({ next: (c) => this.todas.set(c), error: () => this.todas.set([]) });
  }

  /** Solo cuentas de detalle activas (las que reciben movimientos). */
  detalle(): Cuenta[] { return this.todas().filter((c) => c.aceptaMovimiento && c.estado === 'A'); }

  static etiqueta(c?: Cuenta | null): string { return c ? `${c.codigo} · ${c.nombre}` : ''; }
}
