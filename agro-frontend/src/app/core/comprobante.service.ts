import { Injectable, computed, inject } from '@angular/core';
import { AuthService } from './auth.service';

export type ModoComprobante = 'ECF' | 'NCF' | 'NINGUNO';

/** Título impreso por tipo: [NCF tradicional, e-CF]. */
const NOMBRES: Record<number, [string, string]> = {
  31: ['Factura de Crédito Fiscal', 'Factura de Crédito Fiscal Electrónica'],
  32: ['Factura de Consumo', 'Factura de Consumo Electrónica'],
  44: ['Comprobante para Regímenes Especiales', 'Comprobante Electrónico para Regímenes Especiales'],
  45: ['Comprobante Gubernamental', 'Comprobante Electrónico Gubernamental'],
};

/** "e-NCF E320000000001" / "NCF B0200000001" / "" (documento sin comprobante fiscal). */
export function etiquetaNumero(numero?: string | null): string {
  return !numero ? '' : (numero.startsWith('E') ? 'e-NCF ' : 'NCF ') + numero;
}

/** Título del impreso según el comprobante que se emitió (no el modo actual de la empresa). */
export function tituloComprobante(tipoEcf?: number | null, numero?: string | null): string {
  if (!numero) return 'Factura';
  const electronico = numero.startsWith('E');
  return NOMBRES[tipoEcf ?? 0]?.[electronico ? 1 : 0] ?? (electronico ? 'Comprobante Fiscal Electrónico' : 'Comprobante Fiscal');
}

/**
 * Nombres del comprobante fiscal según el modo de la empresa: e-CF (E32), NCF serie B (B02) o sin comprobante.
 * El tipo es el mismo código para ambos (31 ↔ B01, 32 ↔ B02, 34 ↔ B04, 41 ↔ B11, 43 ↔ B13...).
 */
@Injectable({ providedIn: 'root' })
export class ComprobanteService {
  private readonly auth = inject(AuthService);
  readonly modo = computed<ModoComprobante>(() => (this.auth.perfil()?.compania?.modoComprobante as ModoComprobante) ?? 'ECF');
  readonly conComprobante = computed(() => this.modo() !== 'NINGUNO');

  /** "e-CF 32" / "B02" / "" según el modo. */
  tipo(tipoEcf?: number | null): string {
    if (!tipoEcf || this.modo() === 'NINGUNO') return '';
    return this.modo() === 'NCF' ? 'B' + String(tipoEcf - 30).padStart(2, '0') : 'e-CF ' + tipoEcf;
  }

  /** " (e-CF 34)" / " (B04)" / "" para títulos. */
  entreParentesis(tipoEcf: number): string { const t = this.tipo(tipoEcf); return t ? ` (${t})` : ''; }

  /** " · NCF B0100000001" para avisos; vacío sin comprobante. */
  sufijo(numero?: string | null): string { return numero ? ' · ' + etiquetaNumero(numero) : ''; }
}
