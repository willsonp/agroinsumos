import { Component, inject } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { montoEnLetras } from '../../shared/letras';

type Apartado = S['ApartadoResponse'];
const FORMAS: Record<string, string> = { EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', CHEQUE: 'Cheque', TRANSFERENCIA: 'Transferencia' };

/**
 * Acuse de recibo para el cliente (80 mm): al registrar el apartado (mercancía separada y abonos) o al recibir un
 * abono (`abonoId`: monto recibido, balance anterior y pendiente). No es comprobante fiscal: la factura se emite al
 * entregar la mercancía.
 */
@Component({
  selector: 'app-apartado-acuse',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe],
  template: `
    <mat-dialog-content>
      <div class="ticket">
        <div class="centro">
          <div class="empresa">{{ cia?.nombreComercial || cia?.nombre }}</div>
          @if (cia?.nombreComercial && cia?.nombreComercial !== cia?.nombre) { <div class="negrita">{{ cia?.nombre }}</div> }
          <div>RNC: {{ cia?.rnc }}</div>
          @if (cia?.direccion) { <div>{{ cia?.direccion }}</div> }
          @if (cia?.telefono) { <div>Tel: {{ cia?.telefono }}</div> }
          <div class="comprobante">{{ abono ? 'ACUSE DE RECIBO DE ABONO' : 'ACUSE DE APARTADO' }}</div>
          @if (a.estado === 'CANCELADO') { <div class="comprobante">*** CANCELADO ***</div> }
        </div>
        <div class="fila-dos"><span><span class="negrita">FECHA:</span> {{ (abono?.fecha ?? a.fecha) | date: 'dd/MM/yyyy' }}</span>
          <span><span class="negrita">APARTADO:</span> {{ a.numero }}</span></div>
        @if (usuario) { <div><span class="negrita">USUARIO:</span> {{ usuario }}</div> }
        <hr class="doble" />
        <div><span class="negrita">CLIENTE:</span> {{ a.clienteId }}-{{ a.cliente }}</div>
        <div><span class="negrita">CONCEPTO:</span> {{ a.concepto }}</div>
        @if (a.fechaLimite) { <div><span class="negrita">RETIRAR A MÁS TARDAR:</span> {{ a.fechaLimite | date: 'dd/MM/yyyy' }}</div> }
        <hr />
        <table class="detalle">
          <colgroup><col style="width:18%" /><col style="width:52%" /><col style="width:30%" /></colgroup>
          <thead><tr><th class="izq">Cant.</th><th class="izq">Mercancía apartada</th><th>Importe</th></tr></thead>
          <tbody>
            @for (l of a.lineas; track l.linea) {
              <tr class="desc"><td colspan="3">{{ l.descripcion }}</td></tr>
              <tr class="linea"><td class="izq">{{ l.cantidad | number: '1.0-3' }}</td>
                <td class="izq">{{ l.precioUnitario | number: '1.2-2' }}@if (l.descuentoPorcentaje) { -{{ l.descuentoPorcentaje }}% }</td>
                <td>{{ importe(l) | number: '1.2-2' }}</td></tr>
            }
          </tbody>
        </table>
        <table class="totales">
          <tr><td>Sub-Total</td><td>{{ subtotal | number: '1.2-2' }}</td></tr>
          <tr><td>ITBIS</td><td>{{ (a.total ?? 0) - subtotal | number: '1.2-2' }}</td></tr>
          <tr class="total"><td>TOTAL :</td><td>{{ a.total | number: '1.2-2' }}</td></tr>
        </table>
        <hr />
        @if (abono) {
          <table class="totales">
            <tr><td>Balance anterior</td><td>{{ (a.total ?? 0) - antes | number: '1.2-2' }}</td></tr>
            <tr class="total"><td>ABONO :</td><td>{{ abono.monto | number: '1.2-2' }}</td></tr>
            <tr><td>Forma de pago</td><td>{{ forma(abono.forma) }}{{ abono.referencia ? ' ' + abono.referencia : '' }}</td></tr>
          </table>
          <div>{{ letras }}</div>
        } @else {
          <div class="negrita">ABONOS</div>
          <table class="totales">
            @for (b of a.abonos; track b.id) {
              <tr><td>{{ b.fecha | date: 'dd/MM/yyyy' }} {{ b.tipo === 'DEVOLUCION' ? 'Devolución' : forma(b.forma) }}</td>
                <td>{{ b.tipo === 'DEVOLUCION' ? '-' : '' }}{{ b.monto | number: '1.2-2' }}</td></tr>
            } @empty { <tr><td>Sin abonos</td><td>0.00</td></tr> }
          </table>
        }
        <table class="totales">
          <tr><td>Total abonado</td><td>{{ abonadoHasta | number: '1.2-2' }}</td></tr>
          <tr class="total"><td>PENDIENTE :</td><td>{{ (a.total ?? 0) - abonadoHasta | number: '1.2-2' }}</td></tr>
        </table>
        <hr />
        <div class="pequeno">La mercancía queda reservada a su nombre. Al completar el pago se emite la factura y se entrega.
          Conserve este acuse; no es un comprobante fiscal.</div>
        <div class="firmas"><div>Recibido por</div><div>Cliente</div></div>
        <div class="centro">IMPRESO: {{ ahora | date: 'dd-MM-yyyy hh:mm:ss a' }}</div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="no-imprimir">
      <button mat-button mat-dialog-close>Cerrar</button>
      <button mat-flat-button (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
    </mat-dialog-actions>`,
  styleUrl: '../../shared/ticket.scss',
  styles: `.firmas { display: flex; gap: 16px; margin: 40px 0 10px; div { flex: 1; border-top: 1px solid #000; text-align: center; padding-top: 2px; } }
    .pequeno { font-size: 0.9em; margin: 4px 0; }`,
})
export class ApartadoAcuse {
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly usuario = inject(AuthService).perfil()?.nombre;
  private readonly datos = inject<{ apartado: Apartado; abonoId?: number }>(MAT_DIALOG_DATA);
  protected readonly a = this.datos.apartado;
  protected readonly ahora = new Date();
  protected readonly abono = this.datos.abonoId ? this.a.abonos?.find((b) => b.id === this.datos.abonoId) ?? null : null;
  /** Neto abonado antes de este abono (los abonos vienen en orden). */
  protected readonly antes: number;
  protected readonly abonadoHasta: number;
  protected readonly subtotal: number;
  protected readonly letras: string;

  constructor() {
    const neto = (bs: NonNullable<Apartado['abonos']>) => bs.reduce((s, b) => s + (b.tipo === 'DEVOLUCION' ? -1 : 1) * (b.monto ?? 0), 0);
    const todos = this.a.abonos ?? [];
    const i = this.abono ? todos.indexOf(this.abono) : todos.length;
    this.antes = neto(todos.slice(0, i));
    this.abonadoHasta = this.abono ? this.antes + (this.abono.monto ?? 0) : neto(todos);
    this.subtotal = Math.round((this.a.lineas ?? []).reduce((s, l) => s + this.importe(l), 0) * 100) / 100;
    this.letras = montoEnLetras(this.abono?.monto ?? 0);
  }

  protected importe(l: NonNullable<Apartado['lineas']>[number]): number {
    return Math.round((l.cantidad ?? 0) * (l.precioUnitario ?? 0) * (1 - (l.descuentoPorcentaje ?? 0) / 100) * 100) / 100;
  }
  protected forma(f?: string): string { return FORMAS[f ?? ''] ?? f ?? ''; }
  protected imprimir(): void { window.print(); }
}
