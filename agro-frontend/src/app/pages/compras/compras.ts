import { ChangeDetectorRef, Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { CuentasBancarias } from '../../shared/cuentas-bancarias';
import { MONEDAS, Monedas } from '../../shared/monedas';
import type { Pagina, S } from '../../api/tipos';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params, redondear } from '../../shared/http';
import { TASAS } from '../../shared/calculo';
import { Notificacion } from '../../core/notificacion.service';

interface LineaCompra { articulo: S['ArticuloResponse']; cantidad: number; costo: number; descuento: number; tasa: number; lote?: string; vence?: string; }

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
    section { margin-top: 16px; }
    .lote td { background: var(--mat-sys-surface-container-low); font: var(--mat-sys-body-small); color: var(--mat-sys-on-surface-variant);
      .material-symbols-outlined { font-size: 18px; margin-right: 4px; } }
    .lote .celda { margin: 0 12px 0 6px; text-align: left; } .lote .texto { width: 170px; } .lote .fecha { width: 150px; }
    .lote.falta td { border-left: 3px solid var(--mat-sys-error); }`,
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
  protected readonly monedas = MONEDAS;
  private readonly tasas = inject(Monedas);
  private readonly cd = inject(ChangeDetectorRef);

  protected cambiarMoneda(): void {
    this.f.tasa = null;
    if (this.f.moneda !== 'DOP') this.tasas.vigente(this.f.moneda).subscribe((t) => { this.f.tasa = t; this.cd.markForCheck(); });
  }

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

  /** Solo artículos que controlan vencimiento: número de lote (opcional) y fecha (obligatoria). */
  protected fijarLote(i: number, campo: 'lote' | 'vence', v: string): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: v || undefined } : l)));
  }
  protected readonly faltaVencimiento = computed(() => this.lineas().some((l) => l.articulo.manejaVencimiento && !l.vence));
  protected readonly hoy = new Date().toISOString().slice(0, 10);

  protected registrar(): void {
    const req: S['CompraRequest'] = {
      proveedorId: this.f.proveedorId!, condicion: this.f.condicion, diasCredito: this.f.condicion === 'CREDITO' ? this.f.diasCredito : undefined,
      ncfProveedor: this.f.emitir41 ? undefined : this.f.ncf || undefined, flete: this.flete() || undefined,
      itbisRetenido: this.f.itbisRetenido || undefined, isrRetenido: this.f.isrRetenido || undefined,
      tipoRetencionIsr: this.f.isrRetenido ? this.f.tipoRetencionIsr || undefined : undefined,
      tipoBienServicio: this.f.tipoBienServicio, emitirComprobanteCompras: this.f.emitir41 || undefined,
      comentario: this.f.comentario || undefined,
      moneda: this.f.moneda, tasaCambio: this.f.moneda !== 'DOP' ? this.f.tasa ?? undefined : undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad, costoUnitario: l.costo,
        descuento: l.descuento || undefined, tasaItbis: l.tasa,
        lote: l.articulo.manejaVencimiento ? l.lote?.trim() || undefined : undefined,
        fechaVencimiento: l.articulo.manejaVencimiento ? l.vence : undefined })),
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
    this.dialog.open(PagoProveedorDialog, { data: c }).afterClosed().subscribe((r?: { forma: S['PagoRequest']; tasa: number | null }) => {
      if (!r) return;
      const req: S['PagoProveedorRequest'] = { proveedorId: c.proveedorId!, concepto: `Pago compra ${c.numero}`,
        aplicaciones: [{ compraId: c.id!, monto: r.forma.monto }], formas: [r.forma], moneda: c.moneda,
        tasaCambio: c.moneda !== 'DOP' ? r.tasa ?? undefined : undefined };
      this.http.post<S['PagoProveedorResponse']>('/api/v1/cxp/pagos', req).subscribe({
        next: (p) => {
          const dif = p.diferenciaCambiaria ?? 0;
          this.notif.ok('Pago registrado' + (dif ? ` · diferencia cambiaria RD$${Math.abs(dif).toFixed(2)} (${dif > 0 ? 'pérdida' : 'ganancia'})` : ''));
          this.cargar();
        },
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
      tipoBienServicio: '09', itbisRetenido: 0, isrRetenido: 0, tipoRetencionIsr: '', emitir41: false, comentario: '',
      moneda: 'DOP', tasa: null as number | null };
  }
}

@Component({
  selector: 'app-pago-proveedor-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, FormsModule, CurrencyPipe, DecimalPipe],
  template: `
    <h2 mat-dialog-title>Pagar compra {{ compra.numero }}</h2>
    <mat-dialog-content class="grid-form" style="min-width:min(460px,90vw)">
      <p class="ancho-completo">Balance pendiente: <strong>{{ pendiente | currency: compra.moneda }}</strong>
        @if (ext) { <span class="muted"> (RD$ {{ compra.balance }} a la tasa de la compra {{ compra.tasaCambio }})</span> }</p>
      <mat-form-field><mat-label>Monto ({{ compra.moneda }})</mat-label><input matInput type="number" [(ngModel)]="monto" [max]="pendiente" /></mat-form-field>
      @if (ext) {
        <mat-form-field><mat-label>Tasa de hoy (RD$ por {{ compra.moneda }})</mat-label><input matInput type="number" [(ngModel)]="tasa" />
          @if (tasa) { <mat-hint>RD$ {{ monto * tasa | number: '1.2-2' }} · diferencia {{ (monto * tasa) - (monto * (compra.tasaCambio ?? 1)) | number: '1.2-2' }}</mat-hint> }
        </mat-form-field>
      }
      <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="forma">
        <mat-option value="TRANSFERENCIA">Transferencia</mat-option><mat-option value="CHEQUE">Cheque</mat-option>
        @if (!ext) { <mat-option value="EFECTIVO">Efectivo (sale de mi caja)</mat-option> }</mat-select></mat-form-field>
      @if (forma !== 'EFECTIVO') {
        <mat-form-field class="ancho-completo"><mat-label>Sale de la cuenta</mat-label><mat-select [(ngModel)]="cuentaBancariaId">
          <mat-option [value]="null">Sin vincular al módulo Banco</mat-option>
          @for (c of cuentasPermitidas(); track c.id) { <mat-option [value]="c.id">{{ etiqueta(c) }} · disp. {{ c.saldo | currency: c.moneda }}</mat-option> }
        </mat-select>
        @if (cuentaBancariaId && forma === 'CHEQUE') { <mat-hint>Deje el No. de cheque vacío para usar la numeración de la cuenta</mat-hint> }
        </mat-form-field>
      }
      <mat-form-field><mat-label>Referencia / No. cheque</mat-label><input matInput [(ngModel)]="referencia" /></mat-form-field>
      @if (!cuentaBancariaId || forma === 'EFECTIVO') {
        <mat-form-field><mat-label>Banco</mat-label><input matInput [(ngModel)]="banco" /></mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="monto <= 0 || monto > pendiente || (ext && !tasa)" (click)="pagar()">Pagar</button>
    </mat-dialog-actions>`,
})
export class PagoProveedorDialog {
  protected readonly compra = inject<S['CompraResponse']>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PagoProveedorDialog>);
  protected readonly ext = this.compra.moneda !== 'DOP';
  protected readonly pendiente = (this.ext ? this.compra.balanceMoneda : this.compra.balance) ?? 0;
  protected monto = this.pendiente;
  protected tasa: number | null = null;
  /** Cuentas en pesos o en la moneda de la compra. */
  protected readonly cuentasPermitidas = () => this.cuentas.activas().filter((c) => c.moneda === 'DOP' || c.moneda === this.compra.moneda);
  protected forma: 'TRANSFERENCIA' | 'CHEQUE' | 'EFECTIVO' = 'TRANSFERENCIA';
  protected referencia = '';
  protected banco = '';
  protected readonly cuentas = inject(CuentasBancarias);
  protected cuentaBancariaId: number | null = null;
  protected readonly etiqueta = CuentasBancarias.etiqueta;

  constructor() {
    this.cuentas.cargar();
    const cd = inject(ChangeDetectorRef);
    if (this.ext) inject(Monedas).vigente(this.compra.moneda!).subscribe((t) => { this.tasa = t; cd.markForCheck(); });
  }

  protected pagar(): void {
    const cuenta = this.forma === 'EFECTIVO' ? null : this.cuentaBancariaId;
    this.ref.close({ tasa: this.tasa, forma: { forma: this.forma, monto: this.monto, referencia: this.referencia || undefined,
      banco: cuenta ? undefined : this.banco || undefined, cuentaBancariaId: cuenta ?? undefined } });
  }
}
