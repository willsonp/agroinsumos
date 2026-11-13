import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { montoEnLetras } from '../../shared/letras';

const FORMAS: Record<string, string> = { EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', CHEQUE: 'Cheque', TRANSFERENCIA: 'Transferencia',
  SALDO_A_FAVOR: 'Saldo a favor' };

/**
 * Recibo de ingreso (acuse de recibo, 80 mm): cuánto se recibió (en números y letras), qué facturas se pagaron o
 * abonaron, cuánto quedó en cada una y el balance total del cliente después del cobro.
 */
@Component({
  selector: 'app-recibo-dialog',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe],
  template: `
    <mat-dialog-content>
      @if (r(); as r) {
        <div class="ticket">
          <div class="centro">
            <div class="empresa">{{ cia?.nombreComercial || cia?.nombre }}</div>
            @if (cia?.nombreComercial && cia?.nombreComercial !== cia?.nombre) { <div class="negrita">{{ cia?.nombre }}</div> }
            <div>RNC: {{ cia?.rnc }}</div>
            @if (cia?.direccion) { <div>{{ cia?.direccion }}</div> }
            @if (cia?.telefono) { <div>Tel: {{ cia?.telefono }}</div> }
            <div class="comprobante">RECIBO DE INGRESO</div>
            @if (r.estado === 'ANULADO') { <div class="comprobante">*** ANULADO ***</div> }
          </div>
          <div class="fila-dos"><span><span class="negrita">FECHA:</span> {{ r.fecha | date: 'dd/MM/yyyy' }}</span><span><span class="negrita">#RECIBO:</span> {{ r.numero }}</span></div>
          @if (r.atendio) { <div><span class="negrita">USUARIO:</span> {{ r.atendio }}</div> }
          <hr class="doble" />
          <div><span class="negrita">HEMOS RECIBIDO DE:</span> {{ r.clienteId }}-{{ r.clienteNombre }}</div>
          @if (r.clienteRnc) { <div><span class="negrita">RNC/Cédula:</span> {{ r.clienteRnc }}</div> }
          <hr class="doble" />
          <div class="fila-dos negrita"><span>LA SUMA DE:</span><span>RD$ {{ r.totalAplicado | number: '1.2-2' }}</span></div>
          <div>{{ letras }}</div>
          @if (r.concepto) { <div class="bloque"><span class="negrita">Concepto:</span> {{ r.concepto }}</div> }
          <hr />
          <table class="detalle">
            <colgroup><col style="width:46%" /><col style="width:27%" /><col style="width:27%" /></colgroup>
            <thead><tr><th class="izq">Pago de</th><th>Abono</th><th>Balance</th></tr></thead>
            <tbody>
              @for (d of r.documentos; track $index) {
                <tr class="desc"><td colspan="3">{{ d.tipo === 'FACTURA' ? 'Factura' : 'Nota de débito' }} No. {{ d.numero }}@if (d.comprobante) { · {{ d.comprobante }} }</td></tr>
                <tr class="linea"><td class="izq">{{ d.saldado ? 'Saldo' : 'Abono' }}</td><td>{{ d.monto | number: '1.2-2' }}</td>
                  <td>{{ d.balanceDespues == null ? '—' : (d.balanceDespues | number: '1.2-2') }}</td></tr>
                @if ((d.retenciones ?? 0) > 0) { <tr class="linea"><td class="izq">Retenciones</td><td>{{ d.retenciones | number: '1.2-2' }}</td><td></td></tr> }
              }
            </tbody>
          </table>
          <table class="totales">
            @for (p of r.pagos; track $index) { <tr><td>{{ forma(p.forma) }}@if (p.referencia) { ({{ p.referencia }}) }:</td><td>{{ p.monto | number: '1.2-2' }}</td></tr> }
            @if ((r.devuelta ?? 0) > 0) { <tr><td>Devuelta:</td><td>{{ r.devuelta | number: '1.2-2' }}</td></tr> }
          </table>
          @if (r.balanceCliente != null) {
            <hr />
            <table class="totales"><tr class="total"><td>BALANCE :</td><td>{{ r.balanceCliente | number: '1.2-2' }}</td></tr></table>
            @if (r.balanceCliente === 0) { <div class="centro negrita">*** CUENTA SALDADA ***</div> }
          }
          <hr class="doble" />
          <div class="centro pie">{{ cia?.pieTicket || '¡Gracias por su preferencia!' }}</div>
          <div class="centro">IMPRESO: {{ ahora | date: 'dd-MM-yyyy hh:mm:ss a' }}</div>
        </div>
      } @else { <p>Cargando recibo…</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="no-imprimir">
      <button mat-button mat-dialog-close>Cerrar</button>
      <button mat-flat-button [disabled]="!r()" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
    </mat-dialog-actions>`,
  styleUrl: '../../shared/ticket.scss',
})
export class ReciboDialog {
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly r = signal<S['ReciboImpresion'] | null>(null);
  protected letras = '';
  protected readonly ahora = new Date();

  constructor() {
    const { reciboId } = inject<{ reciboId: number }>(MAT_DIALOG_DATA);
    inject(HttpClient).get<S['ReciboImpresion']>(`/api/v1/cxc/recibos/${reciboId}/impresion`).subscribe((r) => {
      this.letras = montoEnLetras(r.totalAplicado ?? 0);
      this.r.set(r);
    });
  }

  protected forma(f?: string): string { return FORMAS[f ?? ''] ?? f ?? ''; }

  protected imprimir(): void { window.print(); }
}
