import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Qr } from '../../shared/qr';

export interface TicketDatos { factura: S['FacturaResponse']; recibo?: S['ReciboResponse']; }

const NOMBRES_ECF: Record<number, string> = {
  31: 'Factura de Crédito Fiscal Electrónica', 32: 'Factura de Consumo Electrónica',
  44: 'Comprobante Electrónico para Regímenes Especiales', 45: 'Comprobante Electrónico Gubernamental',
};

/** Representación impresa de la factura (ticket) con el código QR del timbre e-CF. */
@Component({
  selector: 'app-ticket-dialog',
  imports: [MatDialogModule, MatButtonModule, CurrencyPipe, DatePipe, DecimalPipe, Qr],
  templateUrl: './ticket-dialog.html',
  styleUrl: './ticket-dialog.scss',
})
export class TicketDialog {
  /** "2 Saco x RD$…": la unidad se muestra salvo la genérica (Unidad/UND). */
  protected mostrarUnidad(u?: string | null): boolean { return !!u && !/^(unidad|und|u)$/i.test(u.trim()); }
  protected readonly datos = inject<TicketDatos>(MAT_DIALOG_DATA);
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly timbre = signal<S['EcfTimbreResponse'] | null>(null);
  protected readonly nombreEcf = NOMBRES_ECF[this.datos.factura.tipoEcf ?? 0] ?? 'Comprobante Fiscal Electrónico';

  constructor() {
    const ecfId = this.datos.factura.ecfDocumentoId;
    if (ecfId) inject(HttpClient).get<S['EcfTimbreResponse']>(`/api/v1/ecf/documentos/${ecfId}/timbre`)
      .subscribe((t) => this.timbre.set(t));
  }

  protected imprimir(): void {
    window.print();
  }
}
