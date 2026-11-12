import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Qr } from '../../shared/qr';
import { etiquetaNumero, tituloComprobante } from '../../core/comprobante.service';

export interface TicketDatos { factura: S['FacturaResponse']; recibo?: S['ReciboResponse']; }

/** Representación impresa de la factura (ticket) con el NCF y, si es e-CF, el código QR del timbre. */
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
  protected readonly nombreEcf = tituloComprobante(this.datos.factura.tipoEcf, this.datos.factura.encf);
  protected readonly numeroFiscal = etiquetaNumero(this.datos.factura.encf);

  constructor() {
    const ecfId = this.datos.factura.ecfDocumentoId;
    if (ecfId) inject(HttpClient).get<S['EcfTimbreResponse']>(`/api/v1/ecf/documentos/${ecfId}/timbre`)
      .subscribe((t) => this.timbre.set(t));
  }

  protected imprimir(): void {
    window.print();
  }
}
