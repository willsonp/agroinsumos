import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';

/** Cuentas bancarias activas (para vincular cheques y transferencias con el módulo Banco). */
@Injectable({ providedIn: 'root' })
export class CuentasBancarias {
  private readonly http = inject(HttpClient);
  readonly activas = signal<S['CuentaBancariaResponse'][]>([]);

  cargar(): void {
    this.http.get<Pagina<S['CuentaBancariaResponse']>>('/api/v1/banco/cuentas', { params: params({ size: 200, sort: 'nombre' }) })
      .subscribe({ next: (p) => this.activas.set(p.content.filter((c) => c.estado === 'A')), error: () => this.activas.set([]) });
  }

  static etiqueta(c: S['CuentaBancariaResponse']): string { return `${c.banco} · ${c.nombre} (${c.numero})`; }
}
