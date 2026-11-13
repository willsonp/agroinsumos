import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { valorDiferencia } from './caja-dialogs';

/**
 * Cierre de caja impreso (80 mm, como el sistema anterior): resumen de lo que entró y salió con la cantidad de
 * transacciones, resultado del cuadre (cuadrada / sobrante / faltante) y desglose de monedas contadas más lo cobrado
 * con tarjeta, cheque o depósito. Diferencia = total contado − total del día (positiva: sobrante; negativa: faltante).
 */
@Component({
  selector: 'app-cierre-impreso',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe],
  template: `
    <mat-dialog-content>
      @if (r(); as r) {
        <div class="ticket">
          <div class="centro">
            <div class="empresa">{{ cia?.nombreComercial || cia?.nombre }}</div>
            @if (cia?.direccion) { <div>{{ cia?.direccion }}</div> }
            @if (cia?.telefono) { <div>Tel: {{ cia?.telefono }}</div> }
            <div>RNC: {{ cia?.rnc }}</div>
            <div class="comprobante">CIERRE DE CAJA</div>
          </div>
          <div>IMPRESO: {{ ahora | date: 'dd-MM-yyyy hh:mm a' }}</div>
          <div>APERTURA: {{ s()?.abiertaEn | date: 'dd-MM-yyyy hh:mm a' }}</div>
          <div>CIERRE: {{ s()?.cerradaEn ? (s()?.cerradaEn | date: 'dd-MM-yyyy hh:mm a') : 'CAJA ABIERTA' }}</div>
          <div class="fila-dos"><span>#CAJA: {{ s()?.numero }}</span><span>INICIAL: {{ s()?.montoInicial | number: '1.2-2' }}</span></div>
          <div>USUARIO: {{ r.usuario }}</div>
          <div class="negrita">MONTO CIERRE: {{ r.totalDia?.monto | number: '1.2-2' }}</div>
          <hr class="doble" />
          <table class="totales">
            <tr class="negrita"><td>DESCRIPCION</td><td>TOTAL</td></tr>
          </table>
          <hr class="doble" />
          <table class="totales">
            @for (c of r.conceptos; track c.codigo) {
              <tr><td>{{ c.descripcion }}@if (c.codigo !== 'MONTO_INICIAL') { [{{ c.cantidad }}] }</td><td>{{ c.monto | number: '1.2-2' }}</td></tr>
            }
            <tr><td>Cant. transacciones: {{ r.totalDia?.cantidad }}</td><td></td></tr>
            <tr class="negrita"><td>Subtotal</td><td>{{ r.totalDia?.monto | number: '1.2-2' }}</td></tr>
          </table>
          <hr />
          <table class="totales">
            <tr><td>Efectivo</td><td>{{ r.totalDia?.efectivo | number: '1.2-2' }}</td></tr>
            @if (r.totalDia?.tarjeta) { <tr><td>Tarjetas</td><td>{{ r.totalDia?.tarjeta | number: '1.2-2' }}</td></tr> }
            @if (r.totalDia?.cheque) { <tr><td>Cheques</td><td>{{ r.totalDia?.cheque | number: '1.2-2' }}</td></tr> }
            @if (r.totalDia?.transferencia) { <tr><td>Depósitos / transf.</td><td>{{ r.totalDia?.transferencia | number: '1.2-2' }}</td></tr> }
          </table>
          @if (r.contado; as c) {
            <hr />
            <div class="negrita">RESULTADO: {{ resultado() }}</div>
            <div class="negrita">DIFERENCIA: {{ valorDiferencia(r.diferencia ?? 0) }}</div>
            <div class="centro negrita aviso">****** DESGLOSE DE MONEDAS ******</div>
            <table class="detalle">
              <colgroup><col style="width:50%" /><col style="width:18%" /><col style="width:32%" /></colgroup>
              <thead><tr><th class="izq">Descripción</th><th>Qty</th><th>Total</th></tr></thead>
              <tbody>
                @for (c of r.detalle?.conteo ?? []; track c.denominacion) {
                  <tr><td class="izq">{{ c.denominacion | number: '1.0-2' }} Pesos</td><td>{{ c.cantidad }}</td><td>{{ (c.denominacion ?? 0) * (c.cantidad ?? 0) | number: '1.2-2' }}</td></tr>
                }
                <tr><td class="izq">Monto tarjetas</td><td></td><td>{{ c.tarjeta | number: '1.2-2' }}</td></tr>
                <tr><td class="izq">Monto cheques</td><td></td><td>{{ c.cheque | number: '1.2-2' }}</td></tr>
                <tr><td class="izq">Monto depósitos</td><td></td><td>{{ c.transferencia | number: '1.2-2' }}</td></tr>
              </tbody>
            </table>
            <table class="totales"><tr class="total"><td>TOTALS :</td><td>{{ c.total | number: '1.2-2' }}</td></tr></table>
            @if (s()?.observacion) { <div class="bloque">Observación: {{ s()?.observacion }}</div> }
            <div class="firmas"><div>Cajero</div><div>Supervisor</div></div>
          } @else {
            <div class="centro aviso">Caja abierta: el desglose de monedas sale al cerrarla.</div>
          }
        </div>
      } @else { <p>Cargando cierre…</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="no-imprimir">
      <button mat-button mat-dialog-close>Cerrar</button>
      <button mat-flat-button [disabled]="!r()" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
    </mat-dialog-actions>`,
  styleUrl: '../../shared/ticket.scss',
  styles: `.firmas { display: flex; gap: 16px; margin-top: 36px; div { flex: 1; border-top: 1px solid #000; text-align: center; padding-top: 2px; } }`,
})
export class CierreImpreso {
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly r = signal<S['CierreCajaCierreResumen'] | null>(null);
  protected readonly s = computed(() => this.r()?.detalle?.sesion);
  protected readonly ahora = new Date();
  protected readonly resultado = computed(() => ({ CUADRADA: 'Caja cuadrada', SOBRANTE: 'Caja descuadrada hay un sobrante',
    FALTANTE: 'Caja descuadrada hay un faltante' })[this.r()?.resultado ?? ''] ?? '');
  protected readonly valorDiferencia = valorDiferencia;

  constructor() {
    const { sesionId } = inject<{ sesionId: number }>(MAT_DIALOG_DATA);
    inject(HttpClient).get<S['CierreCajaCierreResumen']>(`/api/v1/caja/sesiones/${sesionId}/cierre`).subscribe((r) => this.r.set(r));
  }

  protected imprimir(): void { window.print(); }
}
