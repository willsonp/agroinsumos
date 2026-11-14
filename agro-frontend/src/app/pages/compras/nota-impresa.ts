import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { montoEnLetras } from '../../shared/letras';

/**
 * Nota de crédito o débito de suplidor impresa (80 mm, como el comprobante de pago): compra afectada, NCF del
 * suplidor, artículos devueltos (si es devolución), montos, el balance que quedó en la compra y firmas.
 */
@Component({
  selector: 'app-nota-impresa',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe],
  template: `
    <mat-dialog-content>
      @if (d(); as d) {
        <div class="ticket">
          <div class="centro">
            <div class="empresa">{{ cia?.nombreComercial || cia?.nombre }}</div>
            @if (cia?.nombreComercial && cia?.nombreComercial !== cia?.nombre) { <div class="negrita">{{ cia?.nombre }}</div> }
            <div>RNC: {{ cia?.rnc }}</div>
            @if (cia?.direccion) { <div>{{ cia?.direccion }}</div> }
            @if (cia?.telefono) { <div>Tel: {{ cia?.telefono }}</div> }
            <div class="comprobante">NOTA DE {{ d.nota?.tipo === 'CREDITO' ? 'CRÉDITO' : 'DÉBITO' }} DE SUPLIDOR</div>
            @if (d.nota?.devuelveInventario) { <div>(Devolución de mercancía)</div> }
            @if (d.nota?.estado === 'ANULADA') { <div class="comprobante">*** ANULADA ***</div> }
          </div>
          <div class="fila-dos"><span><span class="negrita">FECHA:</span> {{ d.nota?.fecha | date: 'dd/MM/yyyy' }}</span><span><span class="negrita">#NOTA:</span> {{ d.nota?.numero }}</span></div>
          @if (d.nota?.ncf) { <div><span class="negrita">NCF:</span> {{ d.nota?.ncf }}</div> }
          @if (d.atendio) { <div><span class="negrita">USUARIO:</span> {{ d.atendio }}</div> }
          <hr class="doble" />
          <div><span class="negrita">SUPLIDOR:</span> {{ d.nota?.proveedorId }}-{{ d.proveedor }}</div>
          @if (d.proveedorRnc) { <div><span class="negrita">RNC/Cédula:</span> {{ d.proveedorRnc }}</div> }
          <div><span class="negrita">AFECTA COMPRA:</span> No. {{ d.nota?.compraNumero }} del {{ d.compraFecha | date: 'dd/MM/yyyy' }}</div>
          @if (d.nota?.compraNcf) { <div><span class="negrita">NCF de la compra:</span> {{ d.nota?.compraNcf }}</div> }
          <hr class="doble" />
          @if (d.nota?.concepto) { <div><span class="negrita">Concepto:</span> {{ d.nota?.concepto }}</div> }
          @if (d.lineas?.length) {
            <hr />
            <table class="detalle">
              <colgroup><col style="width:16%" /><col style="width:54%" /><col style="width:30%" /></colgroup>
              <thead><tr><th class="izq">Cant.</th><th class="izq">Artículo</th><th>Valor</th></tr></thead>
              <tbody>
                @for (l of d.lineas; track $index) {
                  <tr class="desc"><td colspan="3">{{ l.articulo }}</td></tr>
                  <tr class="linea"><td class="izq">{{ l.cantidad | number: '1.0-3' }}</td><td class="izq">{{ l.unidad }}</td>
                    <td>{{ (l.importe ?? 0) + (l.itbis ?? 0) | number: '1.2-2' }}</td></tr>
                }
              </tbody>
            </table>
          }
          <table class="totales">
            <tr><td>Sub-Total</td><td>{{ d.nota?.subtotal | number: '1.2-2' }}</td></tr>
            <tr><td>ITBIS</td><td>{{ d.nota?.itbis | number: '1.2-2' }}</td></tr>
            <tr class="total"><td>TOTAL :</td><td>{{ d.nota?.total | number: '1.2-2' }}</td></tr>
          </table>
          <div>{{ letras }}</div>
          <hr />
          <table class="totales"><tr><td>Balance de la compra:</td><td>{{ d.nota?.balanceCompra | number: '1.2-2' }}</td></tr></table>
          @if (d.nota?.estado === 'ANULADA' && d.nota?.motivoAnulacion) { <div>Motivo de anulación: {{ d.nota?.motivoAnulacion }}</div> }
          <div class="firmas"><div>Preparado por</div><div>Recibido (suplidor)</div></div>
          <div class="centro">IMPRESO: {{ ahora | date: 'dd-MM-yyyy hh:mm:ss a' }}</div>
        </div>
      } @else { <p>Cargando nota…</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="no-imprimir">
      <button mat-button mat-dialog-close>Cerrar</button>
      <button mat-flat-button [disabled]="!d()" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
    </mat-dialog-actions>`,
  styleUrl: '../../shared/ticket.scss',
  styles: `.firmas { display: flex; gap: 16px; margin: 40px 0 10px; div { flex: 1; border-top: 1px solid #000; text-align: center; padding-top: 2px; } }`,
})
export class NotaImpresa {
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly d = signal<S['NotaProveedorNotaImpresion'] | null>(null);
  protected letras = '';
  protected readonly ahora = new Date();

  constructor() {
    const { notaId } = inject<{ notaId: number }>(MAT_DIALOG_DATA);
    inject(HttpClient).get<S['NotaProveedorNotaImpresion']>(`/api/v1/cxp/notas/${notaId}/impresion`).subscribe((d) => {
      this.letras = montoEnLetras(d.nota?.total ?? 0);
      this.d.set(d);
    });
  }

  protected imprimir(): void { window.print(); }
}
