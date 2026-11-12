import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ClienteSelector } from '../../shared/cliente-selector';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { ESCANEADOS_EMPAQUE, LineaCarrito, TipoPrecio, calcular } from '../../shared/calculo';
import { PresentacionSelector } from '../../shared/presentacion-selector';
import { params } from '../../shared/http';
import { TicketDialog } from './ticket-dialog';

type Cotizacion = S['CotizacionResponse'];

/** Cotizaciones (legacy agrega_cotizacion): no mueven inventario ni emiten e-CF hasta facturarse. */
@Component({
  selector: 'app-cotizaciones',
  imports: [PresentacionSelector, CurrencyPipe, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTableModule, MatPaginatorModule, ArticuloBuscador, ClienteSelector],
  templateUrl: './cotizaciones.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 90px; padding: 6px; text-align: right; } .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); }
    section { margin-top: 16px; } .detalle { background: var(--mat-sys-surface-container-low); }`,
})
export class Cotizaciones {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly columnas = ['numero', 'fecha', 'cliente', 'validaHasta', 'total', 'estado', 'acciones'];
  protected readonly pagina = signal<Pagina<Cotizacion> | null>(null);
  protected readonly nueva = signal(false);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly lineas = signal<(LineaCarrito & { precioManual: number | null })[]>([]);
  protected readonly seleccionada = signal<Cotizacion | null>(null);
  protected readonly nombres = signal<Record<number, string>>({});
  protected estado: string | null = 'ABIERTA';
  protected diasValidez = 15;
  protected comentario = '';

  protected readonly lista = computed<TipoPrecio>(() => (this.cliente()?.tipoPrecio as TipoPrecio) ?? 'CONTADO');
  /** Vista previa: si se fija un precio manual se usa en lugar del de lista. */
  protected readonly totales = computed(() => calcular(this.lineas().map((l) => l.precioManual != null
    ? { ...l, articulo: { ...l.articulo, precioContado: l.precioManual, precioCredito: l.precioManual, precioMayor: l.precioManual } } : l),
    this.lista(), !!this.cliente()?.exentoItbis));

  constructor() { this.cargar(); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Cotizacion>>('/api/v1/ventas/cotizaciones', { params: params({ estado: this.estado, page: e?.pageIndex ?? 0, size: 20, sort: 'id,desc' }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected agregar(a: S['ArticuloResponse']): void {
    this.nombres.set({ ...this.nombres(), [a.id!]: a.nombre! });
    this.lineas.set([...this.lineas(), { articulo: a, cantidad: 1, descuentoPorcentaje: 0, precioManual: null, empaque: ESCANEADOS_EMPAQUE.has(a) }]);
  }

  protected cambiarUnidad(i: number, empaque: boolean): void { this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, empaque } : l))); }

  protected fijar(i: number, campo: 'cantidad' | 'descuentoPorcentaje' | 'precioManual', v: number | null): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: campo === 'precioManual' ? (v || null) : Math.max(0, v || 0) } : l)));
  }

  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }

  protected crear(): void {
    const req: S['CotizacionRequest'] = {
      clienteId: this.cliente()!.id!, diasValidez: this.diasValidez, comentario: this.comentario || undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad,
        precioUnitario: l.precioManual ?? undefined, descuentoPorcentaje: l.descuentoPorcentaje || undefined, empaque: l.empaque || undefined })),
    };
    this.http.post<Cotizacion>('/api/v1/ventas/cotizaciones', req).subscribe({
      next: (c) => {
        this.notif.ok(`Cotización ${c.numero} creada`);
        this.nueva.set(false); this.lineas.set([]); this.comentario = '';
        this.cargar(); this.seleccionada.set(c);
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected ver(c: Cotizacion): void {
    this.seleccionada.set(this.seleccionada()?.id === c.id ? null : c);
    // nombres de artículos para el detalle
    for (const l of c.lineas ?? []) {
      if (!this.nombres()[l.articuloId!]) this.http.get<S['ArticuloResponse']>(`/api/v1/inventario/articulos/${l.articuloId}`)
        .subscribe((a) => this.nombres.set({ ...this.nombres(), [a.id!]: a.nombre! }));
    }
  }

  protected facturar(c: Cotizacion): void {
    this.dialog.open(FacturarCotizacionDialog, { data: c }).afterClosed().subscribe((req?: S['CotizacionFacturarRequest']) => {
      if (!req) return;
      this.http.post<S['FacturaResponse']>(`/api/v1/ventas/cotizaciones/${c.id}/facturar`, req).subscribe({
        next: (f) => {
          this.notif.ok(`Factura ${f.numero} emitida · e-NCF ${f.encf}`);
          this.cargar(); this.seleccionada.set(null);
          this.dialog.open(TicketDialog, { data: { factura: f } });
        },
        error: (e) => this.notif.error(e),
      });
    });
  }

  protected anular(c: Cotizacion): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular cotización ${c.numero}`, mensaje: 'La cotización ya no podrá facturarse.', accion: 'Anular', peligro: true } })
      .afterClosed().subscribe((ok) => ok && this.http.post(`/api/v1/ventas/cotizaciones/${c.id}/anular`, {}).subscribe({
        next: () => { this.notif.ok('Cotización anulada'); this.cargar(); this.seleccionada.set(null); }, error: (e) => this.notif.error(e) }));
  }

  protected imprimir(): void { window.print(); }
}

/** Facturar una cotización: condición y comprobante (por defecto, el del cliente). */
@Component({
  selector: 'app-facturar-cotizacion-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, FormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Facturar cotización {{ c.numero }}</h2>
    <mat-dialog-content class="grid-form" style="min-width:min(440px,90vw)">
      <p class="ancho-completo">Total cotizado: <strong>{{ c.total | currency }}</strong>. Se descargará el inventario y se emitirá el e-CF.</p>
      <mat-form-field><mat-label>Condición</mat-label><mat-select [(ngModel)]="condicion">
        <mat-option value="CREDITO">Crédito</mat-option><mat-option value="CONTADO">Contado (cobrar en caja)</mat-option></mat-select></mat-form-field>
      @if (condicion === 'CREDITO') { <mat-form-field><mat-label>Días de crédito (opcional)</mat-label><input matInput type="number" [(ngModel)]="dias" /></mat-form-field> }
      <mat-form-field><mat-label>Comprobante</mat-label><mat-select [(ngModel)]="tipoEcf">
        <mat-option [value]="null">El del cliente</mat-option><mat-option [value]="31">31 · Crédito fiscal</mat-option>
        <mat-option [value]="32">32 · Consumo</mat-option></mat-select></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="facturar()">Facturar</button></mat-dialog-actions>`,
})
export class FacturarCotizacionDialog {
  protected readonly c = inject<Cotizacion>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<FacturarCotizacionDialog>);
  protected condicion: 'CONTADO' | 'CREDITO' = 'CREDITO';
  protected dias: number | null = null;
  protected tipoEcf: number | null = null;

  protected facturar(): void {
    this.ref.close({ condicion: this.condicion, diasCredito: this.condicion === 'CREDITO' ? this.dias ?? undefined : undefined,
      tipoEcf: this.tipoEcf ?? undefined });
  }
}
