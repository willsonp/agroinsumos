import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import type { S } from '../api/tipos';
import { params } from './http';

/** Monedas habituales; las tasas se registran en Banco → Tasas de cambio. */
export const MONEDAS = ['DOP', 'USD', 'EUR'];

@Injectable({ providedIn: 'root' })
export class Monedas {
  private readonly http = inject(HttpClient);

  /** Tasa vigente (pesos por unidad) o null si no hay registrada. */
  vigente(moneda: string, fecha?: string): Observable<number | null> {
    if (moneda === 'DOP') return of(1);
    return new Observable<number | null>((obs) => {
      this.http.get<S['TasaCambioTasa']>('/api/v1/monedas/tasas/vigente', { params: params({ moneda, fecha }) })
        .pipe(map((t) => t.tasa ?? null)).subscribe({ next: (t) => { obs.next(t); obs.complete(); }, error: () => { obs.next(null); obs.complete(); } });
    });
  }
}
