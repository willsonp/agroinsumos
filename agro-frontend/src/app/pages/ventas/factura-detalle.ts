import { Component, inject, input, signal, effect } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatTableModule } from '@angular/material/table';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { EstadoEcf } from '../../shared/estado-ecf';
import { Qr } from '../../shared/qr';
import { descargar } from '../../shared/http';
import { DevolucionDialog } from './devolucion-dialog';
import { NotaDebitoDialog } from './nota-debito-dialog';
import { TicketDialog } from './ticket-dialog';

@Component({
  selector: 'app-factura-detalle',
  imports: [RouterLink, CurrencyPipe, DatePipe, DecimalPipe, MatButtonModule, MatTableModule, EstadoEcf, Qr],
  templateUrl: './factura-detalle.html',
  styles: `.cabecera { display: grid; grid-template-columns: 1fr auto; gap: 16px; }
    .datos { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 8px 16px;
      label { display: block; font: var(--mat-sys-label-small); color: var(--mat-sys-on-surface-variant); } }
    .totales td { padding: 4px 8px; } .totales .total td { font: var(--mat-sys-title-large); }
    section { margin-top: 16px; }`,
})
export class FacturaDetalle {
  readonly id = input.required<string>();
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly factura = signal<S['FacturaResponse'] | null>(null);
  protected readonly ecf = signal<S['EcfResponse'] | null>(null);
  protected readonly timbre = signal<S['EcfTimbreResponse'] | null>(null);
  protected readonly devoluciones = signal<S['DevolucionResponse'][]>([]);
  protected readonly columnas = ['linea', 'descripcion', 'cantidad', 'precio', 'descuento', 'itbis', 'importe', 'devuelta'];

  constructor() {
    effect(() => this.cargar(this.id()));
  }

  protected cargar(id: string): void {
    this.http.get<S['FacturaResponse']>(`/api/v1/ventas/facturas/${id}`).subscribe({
      next: (f) => {
        this.factura.set(f);
        if (f.ecfDocumentoId) {
          this.http.get<S['EcfResponse']>(`/api/v1/ecf/documentos/${f.ecfDocumentoId}`).subscribe((e) => this.ecf.set(e));
          this.http.get<S['EcfTimbreResponse']>(`/api/v1/ecf/documentos/${f.ecfDocumentoId}/timbre`).subscribe((t) => this.timbre.set(t));
        }
        this.http.get<S['DevolucionResponse'][]>(`/api/v1/ventas/facturas/${id}/devoluciones`).subscribe((d) => this.devoluciones.set(d));
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected imprimir(): void {
    this.dialog.open(TicketDialog, { data: { factura: this.factura()! } });
  }

  protected xml(): void {
    const f = this.factura()!;
    this.http.get(`/api/v1/ecf/documentos/${f.ecfDocumentoId}/xml`, { responseType: 'blob' })
      .subscribe((b) => descargar(b, `${f.encf}.xml`));
  }

  protected anular(): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: 'Anular factura', peligro: true, accion: 'Anular',
      pedirMotivo: 'Motivo de la anulación',
      mensaje: 'Se emitirá una nota de crédito electrónica (e-CF 34) por todo lo pendiente y la mercancía reingresará al inventario.' } })
      .afterClosed().subscribe((motivo?: string) => {
        if (!motivo) return;
        this.http.post<S['DevolucionResponse']>(`/api/v1/ventas/facturas/${this.id()}/anular`, { motivo }).subscribe({
          next: (d) => { this.notif.ok(`Factura anulada · Nota de crédito ${d.encf}`); this.cargar(this.id()); },
          error: (e) => this.notif.error(e),
        });
      });
  }

  protected notaDebito(): void {
    this.dialog.open(NotaDebitoDialog, { data: this.factura() }).afterClosed().subscribe((req?: S['NotaDebitoRequest']) => {
      if (!req) return;
      this.http.post<S['NotaDebitoResponse']>('/api/v1/cxc/notas-debito', req).subscribe({
        next: (n) => this.notif.ok(`Nota de débito ${n.encf} por ${n.total} (cobrable en Cobros)`),
        error: (e) => this.notif.error(e),
      });
    });
  }

  protected devolver(): void {
    this.dialog.open(DevolucionDialog, { data: this.factura(), width: '720px' }).afterClosed()
      .subscribe((req?: S['DevolucionRequest']) => {
        if (!req) return;
        this.http.post<S['DevolucionResponse']>('/api/v1/ventas/devoluciones', req).subscribe({
          next: (d) => { this.notif.ok(`Nota de crédito ${d.encf} por ${d.total}`); this.cargar(this.id()); },
          error: (e) => this.notif.error(e),
        });
      });
  }
}
