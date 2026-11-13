import { Component, input } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import type { S } from '../../api/tipos';

const FORMAS: Record<string, string> = { EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', CHEQUE: 'Cheque', TRANSFERENCIA: 'Transferencia',
  SALDO_A_FAVOR: 'Saldo a favor' };

const TIPOS: Record<string, string> = { COBRO: 'Cobro', INGRESO: 'Ingreso', EGRESO: 'Egreso', PAGO_PROVEEDOR: 'Pago a suplidor',
  ANULACION_COBRO: 'Anulación de cobro', ANULACION_PAGO: 'Anulación de pago' };

/** Cuadre por forma de pago y movimientos de una sesión de caja (arqueo). */
@Component({
  selector: 'app-sesion-detalle',
  imports: [CurrencyPipe, DatePipe],
  template: `
    @if (sesion(); as s) {
      <div class="cuadre">
        <div class="tarjeta kpi"><label>Monto inicial</label>{{ s.cuadre?.montoInicial | currency }}</div>
        <div class="tarjeta kpi destacado"><label>Efectivo esperado</label>{{ s.cuadre?.efectivoEsperado | currency }}</div>
        @if (s.cuadre?.efectivoContado != null) {
          <div class="tarjeta kpi"><label>Contado</label>{{ s.cuadre?.efectivoContado | currency }}</div>
          <div class="tarjeta kpi" [class.negativo]="(s.cuadre?.diferencia ?? 0) !== 0"><label>Diferencia</label>{{ s.cuadre?.diferencia | currency }}</div>
        }
      </div>
      <h3>Por forma de pago</h3>
      <table class="tabla">
        <tr><th>Forma</th><th class="num">Inicial</th><th class="num">Cobros</th><th class="num">Ingresos</th><th class="num">Salidas</th>
          <th class="num">En caja</th></tr>
        @for (t of formas(s); track t.forma) {
          <tr><td>{{ forma(t.forma) }}</td><td class="num">{{ t.inicial | currency }}</td><td class="num">{{ t.cobros | currency }}</td>
            <td class="num">{{ t.ingresos | currency }}</td><td class="num">{{ t.salidas | currency }}</td>
            <td class="num" [class.negativo]="t.enCaja < 0"><strong>{{ t.enCaja | currency }}</strong></td></tr>
        }
      </table>
      <p class="muted pequeno">En caja = inicial + cobros + ingresos − salidas. En efectivo debe coincidir con el efectivo esperado.</p>
    }
    @if (movimientos().length) {
      <h3>Movimientos</h3>
      <table class="tabla">
        <tr><th>Hora</th><th>Tipo</th><th>Forma</th><th>Descripción</th><th class="num">Monto</th></tr>
        @for (m of movimientos(); track m.id) {
          <tr><td>{{ m.fecha | date: 'shortTime' }}</td><td>{{ tipo(m.tipo) }}</td><td>{{ forma(m.forma) }}</td><td>{{ m.descripcion }}</td>
            <td class="num" [class.negativo]="esSalida(m.tipo)">{{ esSalida(m.tipo) ? '-' : '' }}{{ m.monto | currency }}</td></tr>
        }
      </table>
    }`,
  styles: `.cuadre { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
    .kpi { font: var(--mat-sys-headline-small); label { display: block; font: var(--mat-sys-label-medium); color: var(--mat-sys-on-surface-variant); } }
    .destacado { color: var(--mat-sys-primary); } .negativo { color: var(--mat-sys-error); }
    .tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }`,
})
export class SesionDetalle {
  readonly sesion = input<S['CajaSesionResponse'] | null>(null);
  readonly movimientos = input<S['CajaMovimientoResponse'][]>([]);
  protected tipo(t?: string): string { return TIPOS[t ?? ''] ?? t ?? ''; }

  protected forma(f?: string): string { return FORMAS[f ?? ''] ?? f ?? ''; }

  /** El monto inicial es efectivo: va en la fila de efectivo para que "En caja" cuadre con el efectivo esperado. */
  protected formas(s: S['CajaSesionResponse']): { forma: string; inicial: number; cobros: number; ingresos: number; salidas: number; enCaja: number }[] {
    const inicial = s.cuadre?.montoInicial ?? 0;
    const filas = (s.cuadre?.porForma ?? []).map((t) => {
      const ini = t.forma === 'EFECTIVO' ? inicial : 0;
      return { forma: t.forma ?? '', inicial: ini, cobros: t.cobros ?? 0, ingresos: t.ingresos ?? 0, salidas: t.salidas ?? 0, enCaja: ini + (t.neto ?? 0) };
    });
    if (!filas.some((f) => f.forma === 'EFECTIVO')) filas.unshift({ forma: 'EFECTIVO', inicial, cobros: 0, ingresos: 0, salidas: 0, enCaja: inicial });
    return filas;
  }
  protected esSalida(t?: string): boolean { return ['EGRESO', 'PAGO_PROVEEDOR', 'ANULACION_COBRO'].includes(t ?? ''); }
}
