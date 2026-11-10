import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params, redondear } from '../../shared/http';
import { TASAS } from '../../shared/calculo';
import { Notificacion } from '../../core/notificacion.service';

interface LineaCompra { articulo: S['ArticuloResponse']; cantidad: number; costo: number; descuento: number; tasa: number; }

const TIPOS_606 = [['01', 'Gastos de personal'], ['02', 'Trabajos, suministros y servicios'], ['03', 'Arrendamientos'], ['04', 'Activos fijos'],
  ['05', 'Representación'], ['06', 'Otras deducciones admitidas'], ['07', 'Gastos financieros'], ['08', 'Gastos extraordinarios'],
  ['09', 'Compras que forman parte del costo de venta'], ['10', 'Adquisiciones de activos'], ['11', 'Seguros']];

@Component({
  selector: 'app-compras',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTableModule, MatPaginatorModule, ArticuloBuscador],
  templateUrl: './compras.html',
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 100px; padding: 6px; text-align: right; } .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); }
    section { margin-top: 16px; }`,
})
export class Compras {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly tipos606 = TIPOS_606;
  protected readonly proveedores = signal<S['ProveedorResponse'][]>([]);
  protected readonly pagina = signal<Pagina<S['CompraResponse']> | null>(null);
  protected readonly lineas = signal<LineaCompra[]>([]);
  protected readonly nueva = signal(false);
  protected readonly columnas = ['numero', 'fecha', 'proveedor', 'ncf', 'total', 'balance', 'estado', 'acciones'];
  protected f = this.vacia();
  protected flete = signal(0);

  protected readonly totales = computed(() => {
    let subtotal = 0, itbis = 0;
    for (const l of this.lineas()) {
      const importe = redondear(l.cantidad * l.costo - l.descuento);
      subtotal += importe;
      itbis += redondear(importe * l.tasa / 100);
    }
    return { subtotal: redondear(subtotal), itbis: redondear(itbis), total: redondear(subtotal + itbis + this.flete()) };
  });

  constructor() {
    this.http.get<Pagina<S['ProveedorResponse']>>('/api/v1/catalogos/proveedores', { params: params({ size: 500, sort: 'nombre' }) })
      .subscribe((p) => this.proveedores.set(p.content.filter((x) => x.estado === 'A')));
    this.cargar();
  }

  protected nombreProveedor(id?: number): string { return this.proveedores().find((p) => p.id === id)?.nombre ?? String(id); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<S['CompraResponse']>>('/api/v1/compras', { params: params({ page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe((p) => this.pagina.set(p));
  }

  protected agregar(a: S['ArticuloResponse']): void {
    this.lineas.set([...this.lineas(), { articulo: a, cantidad: 1, costo: a.costo ?? 0, descuento: 0, tasa: TASAS[a.indicadorItbis ?? 4] }]);
  }

  protected fijar(i: number, campo: 'cantidad' | 'costo' | 'descuento' | 'tasa', v: number): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: Math.max(0, v || 0) } : l)));
  }

  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }

  protected registrar(): void {
    const req: S['CompraRequest'] = {
      proveedorId: this.f.proveedorId!, condicion: this.f.condicion, diasCredito: this.f.condicion === 'CREDITO' ? this.f.diasCredito : undefined,
      ncfProveedor: this.f.emitir41 ? undefined : this.f.ncf || undefined, flete: this.flete() || undefined,
      itbisRetenido: this.f.itbisRetenido || undefined, isrRetenido: this.f.isrRetenido || undefined,
      tipoRetencionIsr: this.f.isrRetenido ? this.f.tipoRetencionIsr || undefined : undefined,
      tipoBienServicio: this.f.tipoBienServicio, emitirComprobanteCompras: this.f.emitir41 || undefined,
      comentario: this.f.comentario || undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad, costoUnitario: l.costo,
        descuento: l.descuento || undefined, tasaItbis: l.tasa })),
    };
    this.http.post<S['CompraResponse']>('/api/v1/compras', req).subscribe({
      next: (c) => {
        this.notif.ok(`Compra ${c.numero} registrada${c.encfCompra ? ' · e-CF 41 ' + c.encfCompra : ''}`);
        this.nueva.set(false); this.lineas.set([]); this.flete.set(0); this.f = this.vacia(); this.cargar();
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected pagar(c: S['CompraResponse']): void {
    this.dialog.open(PagoProveedorDialog, { data: c }).afterClosed().subscribe((forma?: S['PagoRequest']) => {
      if (!forma) return;
      this.http.post('/api/v1/cxp/pagos', { proveedorId: c.proveedorId, concepto: `Pago compra ${c.numero}`,
        aplicaciones: [{ compraId: c.id, monto: forma.monto }], formas: [forma] }).subscribe({
        next: () => { this.notif.ok('Pago registrado'); this.cargar(); },
        error: (e) => this.notif.error(e),
      });
    });
  }

  protected anular(c: S['CompraResponse']): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular compra ${c.numero}`, peligro: true, accion: 'Anular',
      mensaje: 'Se revertirá la entrada al inventario. Solo es posible si la compra no tiene pagos.' } })
      .afterClosed().subscribe((ok) => ok && this.http.post(`/api/v1/compras/${c.id}/anular`, {}).subscribe({
        next: () => { this.notif.ok('Compra anulada'); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }

  private vacia() {
    return { proveedorId: null as number | null, ncf: '', condicion: 'CREDITO' as 'CONTADO' | 'CREDITO', diasCredito: 30,
      tipoBienServicio: '09', itbisRetenido: 0, isrRetenido: 0, tipoRetencionIsr: '', emitir41: false, comentario: '' };
  }
}

@Component({
  selector: 'app-pago-proveedor-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, FormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Pagar compra {{ compra.numero }}</h2>
    <mat-dialog-content class="grid-form" style="min-width:min(460px,90vw)">
      <p class="ancho-completo">Balance pendiente: <strong>{{ compra.balance | currency }}</strong></p>
      <mat-form-field><mat-label>Monto</mat-label><input matInput type="number" [(ngModel)]="monto" [max]="compra.balance ?? 0" /></mat-form-field>
      <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="forma">
        <mat-option value="TRANSFERENCIA">Transferencia</mat-option><mat-option value="CHEQUE">Cheque</mat-option>
        <mat-option value="EFECTIVO">Efectivo (sale de mi caja)</mat-option></mat-select></mat-form-field>
      <mat-form-field><mat-label>Referencia / No. cheque</mat-label><input matInput [(ngModel)]="referencia" /></mat-form-field>
      <mat-form-field><mat-label>Banco</mat-label><input matInput [(ngModel)]="banco" /></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="monto <= 0 || monto > (compra.balance ?? 0)" (click)="pagar()">Pagar</button>
    </mat-dialog-actions>`,
})
export class PagoProveedorDialog {
  protected readonly compra = inject<S['CompraResponse']>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PagoProveedorDialog>);
  protected monto = this.compra.balance ?? 0;
  protected forma: 'TRANSFERENCIA' | 'CHEQUE' | 'EFECTIVO' = 'TRANSFERENCIA';
  protected referencia = '';
  protected banco = '';

  protected pagar(): void {
    this.ref.close({ forma: this.forma, monto: this.monto, referencia: this.referencia || undefined, banco: this.banco || undefined });
  }
}
