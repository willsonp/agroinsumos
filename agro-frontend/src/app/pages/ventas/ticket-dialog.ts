import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe, SlicePipe, UpperCasePipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Qr } from '../../shared/qr';
import { etiquetaNumero, tituloComprobante } from '../../core/comprobante.service';

export interface TicketDatos { factura: S['FacturaResponse']; recibo?: S['ReciboResponse']; }

const FORMAS: Record<string, string> = { EFECTIVO: 'Efectivo', TARJETA: 'Tarjeta', CHEQUE: 'Cheque', TRANSFERENCIA: 'Transferencia', SALDO_A_FAVOR: 'Saldo a favor' };

/**
 * Ticket POS (80 mm): encabezado de la empresa, comprobante, datos del cliente, detalle con precio sin ITBIS y valor
 * con ITBIS, totales, forma de pago, "Original"/"Reimpresión", quién atendió y, si es e-CF, QR y fecha de firma.
 */
@Component({
  selector: 'app-ticket-dialog',
  imports: [MatDialogModule, MatButtonModule, DatePipe, DecimalPipe, SlicePipe, UpperCasePipe, Qr],
  templateUrl: './ticket-dialog.html',
  styleUrl: '../../shared/ticket.scss',
})
export class TicketDialog {
  private readonly http = inject(HttpClient);
  protected readonly datos = inject<TicketDatos>(MAT_DIALOG_DATA);
  protected readonly f = this.datos.factura;
  protected readonly cia = inject(AuthService).perfil()?.compania;
  protected readonly timbre = signal<S['EcfTimbreResponse'] | null>(null);
  protected readonly extra = signal<S['TicketFacturaTicketResponse'] | null>(null);
  protected readonly nombreEcf = tituloComprobante(this.f.tipoEcf, this.f.encf);
  protected readonly numeroFiscal = etiquetaNumero(this.f.encf);
  protected readonly items = this.f.lineas?.length ?? 0;
  protected readonly subtotal = (this.f.montoGravado ?? 0) + (this.f.montoExento ?? 0);
  protected readonly impresion = computed(() => (this.extra()?.impresiones ?? 0) > 0 ? 'Reimpresión' : 'Original');

  constructor() {
    const ecfId = this.f.ecfDocumentoId;
    if (ecfId) this.http.get<S['EcfTimbreResponse']>(`/api/v1/ecf/documentos/${ecfId}/timbre`).subscribe((t) => this.timbre.set(t));
    this.http.get<S['TicketFacturaTicketResponse']>(`/api/v1/ventas/facturas/${this.f.id}/ticket`)
      .subscribe((x) => this.extra.set(x));
  }

  /** Valor de la línea con ITBIS (el precio se muestra sin ITBIS, como el sistema anterior). */
  protected valor(l: S['FacturaLineaResponse']): number { return (l.importe ?? 0) + (l.itbis ?? 0); }

  protected forma(f?: string): string { return FORMAS[f ?? ''] ?? f ?? ''; }

  protected imprimir(): void {
    window.print();
    // Cuenta la impresión: la próxima saldrá como "Reimpresión"
    this.http.post<S['TicketFacturaImpresionResponse']>(`/api/v1/ventas/facturas/${this.f.id}/ticket/impresion`, {})
      .subscribe((r) => this.extra.update((x) => x ? { ...x, impresiones: r.impresiones } : x));
  }
}
