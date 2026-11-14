import { Component, computed, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import type { S } from '../../api/tipos';
import { ClienteSelector } from '../../shared/cliente-selector';
import { redondear } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';
import { PagoDialog } from '../ventas/pago-dialog';
import { ReciboDialog } from './recibo-dialog';

type Pendiente = S['EstadoCuentaDocumentoPendiente'];
interface Abono { doc: Pendiente; monto: number; retItbis: number; retIsr: number; }

/** Recibo de ingreso (legacy FrmReciboIngreso): abona facturas y notas de débito pendientes. */
@Component({
  selector: 'app-cobros',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule, ClienteSelector],
  templateUrl: './cobros.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 110px; padding: 6px; text-align: right; } .vencido { color: var(--mat-sys-error); }
    .pie { display: flex; gap: 16px; align-items: center; margin-top: 16px; flex-wrap: wrap; }
    .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); }`,
})
export class Cobros {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly abonos = signal<Abono[]>([]);
  protected readonly ultimo = signal<S['ReciboResponse'] | null>(null);
  protected conRetenciones = false;
  protected concepto = '';
  /** Monto que entrega el cliente para repartir entre sus documentos (legacy: botón Distribuir monto). */
  protected montoDistribuir: number | null = null;

  protected readonly totalAbono = computed(() => redondear(this.abonos().reduce((s, a) => s + a.monto, 0)));
  protected readonly totalRetenciones = computed(() => redondear(this.abonos().reduce((s, a) => s + a.retItbis + a.retIsr, 0)));
  protected readonly excede = computed(() => this.abonos().some((a) => a.monto + a.retItbis + a.retIsr > (a.doc.balance ?? 0) + 0.001));

  constructor() {
    effect(() => {
      const c = this.cliente();
      this.abonos.set([]);
      if (c) this.cargarPendientes(c.id!);
    });
  }

  private cargarPendientes(id: number): void {
    this.http.get<Pendiente[]>(`/api/v1/cxc/clientes/${id}/pendientes`).subscribe({
      next: (docs) => this.abonos.set(docs.map((doc) => ({ doc, monto: 0, retItbis: 0, retIsr: 0 }))),
      error: (e) => this.notif.error(e),
    });
  }

  protected fijar(i: number, campo: 'monto' | 'retItbis' | 'retIsr', v: number): void {
    this.abonos.set(this.abonos().map((a, j) => (j === i ? { ...a, [campo]: Math.max(0, v || 0) } : a)));
  }

  protected saldarTodo(): void {
    this.abonos.set(this.abonos().map((a) => ({ ...a, monto: redondear((a.doc.balance ?? 0) - a.retItbis - a.retIsr) })));
  }

  /**
   * Reparte el monto entre los documentos pendientes, del más antiguo (vence primero) al más reciente: salda los que
   * alcance y el resto queda como abono al siguiente.
   */
  protected distribuir(): void {
    let restante = redondear(this.montoDistribuir ?? 0);
    if (restante <= 0) { this.notif.error(null, 'Indique el monto a distribuir'); return; }
    const orden = this.abonos().map((a, i) => ({ i, clave: a.doc.vencimiento ?? a.doc.fecha ?? '' }))
      .sort((x, y) => x.clave.localeCompare(y.clave) || x.i - y.i).map((x) => x.i);
    const montos = new Map<number, number>();
    for (const i of orden) {
      const a = this.abonos()[i];
      const disponible = redondear((a.doc.balance ?? 0) - a.retItbis - a.retIsr);
      const abono = Math.min(restante, Math.max(0, disponible));
      montos.set(i, abono);
      restante = redondear(restante - abono);
    }
    this.abonos.set(this.abonos().map((a, i) => ({ ...a, monto: montos.get(i) ?? 0 })));
    if (restante > 0) this.notif.error(null, `El monto supera la deuda en ${restante.toFixed(2)}: se aplicó solo lo pendiente`);
  }

  protected imprimir(id: number): void { this.dialog.open(ReciboDialog, { data: { reciboId: id } }); }

  protected cobrar(): void {
    const aplicaciones = this.abonos().filter((a) => a.monto + a.retItbis + a.retIsr > 0).map((a) => ({
      documentoTipo: a.doc.tipo as 'FACTURA' | 'NOTA_DEBITO', documentoId: a.doc.id!, monto: a.monto,
      retencionItbis: a.retItbis || undefined, retencionIsr: a.retIsr || undefined,
    }));
    if (!aplicaciones.length) { this.notif.error(null, 'Indique los montos a abonar'); return; }
    const enviar = (pagos: S['PagoRequest'][]) =>
      this.http.post<S['ReciboResponse']>('/api/v1/cxc/recibos', {
        clienteId: this.cliente()!.id, concepto: this.concepto || undefined, aplicaciones, pagos,
      }).subscribe({
        next: (r) => {
          this.ultimo.set(r);
          this.notif.ok(`Recibo ${r.numero} registrado`);
          this.concepto = '';
          this.montoDistribuir = null;
          this.imprimir(r.id!);   // constancia de pago para el cliente con el balance que le queda
          this.cargarPendientes(this.cliente()!.id!);
        },
        error: (e) => this.notif.error(e),
      });
    if (this.totalAbono() === 0) { enviar([]); return; } // solo retenciones
    this.dialog.open(PagoDialog, { data: { total: this.totalAbono(), saldoAFavor: this.cliente()?.saldoAFavor ?? 0 }, width: 'min(680px, 96vw)', maxWidth: '96vw' })
      .afterClosed().subscribe((pagos?: S['PagoRequest'][]) => pagos && enviar(pagos));
  }
}
