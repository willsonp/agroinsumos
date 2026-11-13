import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { montoEnLetras } from '../../shared/letras';

const FORMAS: Record<string, string> = { EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', CHEQUE: 'Cheque', TRANSFERENCIA: 'Transferencia' };

/**
 * Comprobante de pago a suplidor (80 mm, como el recibo de ingreso): cuánto se pagó (en números y letras), qué compras
 * se saldaron o abonaron, cuánto quedó en cada una y lo que se le sigue debiendo al suplidor.
 */
@Component({
  selector: 'app-comprobante-pago-dialog',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe],
  template: `
    <mat-dialog-content>
      @if (p(); as p) {
        <div class="ticket">
          <div class="centro">
            <div class="empresa">{{ cia?.nombreComercial || cia?.nombre }}</div>
            @if (cia?.nombreComercial && cia?.nombreComercial !== cia?.nombre) { <div class="negrita">{{ cia?.nombre }}</div> }
            <div>RNC: {{ cia?.rnc }}</div>
            @if (cia?.direccion) { <div>{{ cia?.direccion }}</div> }
            @if (cia?.telefono) { <div>Tel: {{ cia?.telefono }}</div> }
            <div class="comprobante">COMPROBANTE DE PAGO A SUPLIDOR</div>
            @if (p.estado === 'ANULADO') { <div class="comprobante">*** ANULADO ***</div> }
          </div>
          <div class="fila-dos"><span><span class="negrita">FECHA:</span> {{ p.fecha | date: 'dd/MM/yyyy' }}</span><span><span class="negrita">#PAGO:</span> {{ p.numero }}</span></div>
          @if (p.atendio) { <div><span class="negrita">USUARIO:</span> {{ p.atendio }}</div> }
          <hr class="doble" />
          <div><span class="negrita">PAGADO A:</span> {{ p.proveedorId }}-{{ p.proveedor }}</div>
          @if (p.proveedorRnc) { <div><span class="negrita">RNC/Cédula:</span> {{ p.proveedorRnc }}</div> }
          <hr class="doble" />
          <div class="fila-dos negrita"><span>LA SUMA DE:</span><span>{{ simbolo }} {{ p.total | number: '1.2-2' }}</span></div>
          @if (p.moneda === 'DOP') { <div>{{ letras }}</div> } @else { <div>Tasa: {{ p.tasaCambio | number: '1.2-4' }}</div> }
          @if (p.concepto) { <div class="bloque"><span class="negrita">Concepto:</span> {{ p.concepto }}</div> }
          <hr />
          <table class="detalle">
            <colgroup><col style="width:46%" /><col style="width:27%" /><col style="width:27%" /></colgroup>
            <thead><tr><th class="izq">Pago de</th><th>Monto</th><th>Balance</th></tr></thead>
            <tbody>
              @for (d of p.documentos; track $index) {
                <tr class="desc"><td colspan="3">Compra No. {{ d.numero }}@if (d.ncfProveedor) { · {{ d.ncfProveedor }} }</td></tr>
                <tr class="linea"><td class="izq">{{ d.saldado ? 'Saldo' : 'Abono' }}</td><td>{{ d.monto | number: '1.2-2' }}</td>
                  <td>{{ d.balanceDespues == null ? '—' : (d.balanceDespues | number: '1.2-2') }}</td></tr>
              }
            </tbody>
          </table>
          <table class="totales">
            @for (f of p.formas; track $index) { <tr><td>{{ forma(f.forma) }}@if (f.referencia) { ({{ f.referencia }}) }:</td><td>{{ f.monto | number: '1.2-2' }}</td></tr> }
          </table>
          @if (p.balanceProveedor != null) {
            <hr />
            <table class="totales"><tr class="total"><td>BALANCE :</td><td>{{ p.balanceProveedor | number: '1.2-2' }}</td></tr></table>
            <div class="centro">Pendiente con el suplidor (RD$)</div>
            @if (p.balanceProveedor === 0) { <div class="centro negrita">*** CUENTA SALDADA ***</div> }
          }
          <div class="firmas"><div>Entregado por</div><div>Recibido por</div></div>
          <div class="centro">IMPRESO: {{ ahora | date: 'dd-MM-yyyy hh:mm:ss a' }}</div>
        </div>
      } @else { <p>Cargando comprobante…</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="no-imprimir">
      <button mat-button mat-dialog-close>Cerrar</button>
      <button mat-flat-button [disabled]="!p()" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
    </mat-dialog-actions>`,
  styleUrl: '../../shared/ticket.scss',
  styles: `.firmas { display: flex; gap: 16px; margin: 40px 0 10px; div { flex: 1; border-top: 1px solid #000; text-align: center; padding-top: 2px; } }`,
})
export class ComprobantePagoDialog {
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly p = signal<S['PagoProveedorPagoImpresion'] | null>(null);
  protected letras = '';
  protected simbolo = 'RD$';
  protected readonly ahora = new Date();

  constructor() {
    const { pagoId } = inject<{ pagoId: number }>(MAT_DIALOG_DATA);
    inject(HttpClient).get<S['PagoProveedorPagoImpresion']>(`/api/v1/cxp/pagos/${pagoId}/impresion`).subscribe((p) => {
      this.letras = montoEnLetras(p.total ?? 0);
      this.simbolo = p.moneda === 'DOP' ? 'RD$' : p.moneda ?? '';
      this.p.set(p);
    });
  }

  protected forma(f?: string): string { return FORMAS[f ?? ''] ?? f ?? ''; }

  protected imprimir(): void { window.print(); }
}
