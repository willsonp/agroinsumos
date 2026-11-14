import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params, redondear } from '../../shared/http';

type Nota = S['NotaProveedorResponse'];
type Compra = S['CompraResponse'];

/** Notas de crédito / débito recibidas de suplidores (legacy notacreditocxp / notadebitocxp). */
import { Fecha } from '../../shared/fecha';
import { ProveedorSelector } from '../../shared/proveedor-selector';
@Component({
  selector: 'app-notas-proveedor',
  imports: [ProveedorSelector, Fecha, CurrencyPipe, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatPaginatorModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Notas de suplidores</h1><span class="espaciador"></span>
        @if (!nuevo()) { <button mat-flat-button (click)="abrir()"><span class="material-symbols-outlined">add</span> Nueva nota</button> }</div>

      @if (nuevo()) {
        <div class="tarjeta">
          <mat-button-toggle-group [(ngModel)]="f.tipo" aria-label="Tipo de nota">
            <mat-button-toggle value="CREDITO">Nota de crédito (rebaja la deuda)</mat-button-toggle>
            <mat-button-toggle value="DEBITO">Nota de débito (aumenta la deuda)</mat-button-toggle>
          </mat-button-toggle-group>
          <div class="grid-form" style="margin-top:12px">
            <app-proveedor-selector [requerido]="true" [(ngModel)]="f.proveedorId" (cambio)="cargarCompras()" />
            <mat-form-field><mat-label>Compra afectada</mat-label><mat-select [ngModel]="compra()?.id" (ngModelChange)="elegirCompra($event)">
              @for (c of compras(); track c.id) {
                <mat-option [value]="c.id">No. {{ c.numero }} · {{ c.ncfProveedor ?? 'sin NCF' }} · {{ c.fecha | date: 'dd/MM/yyyy' }} · bal. {{ c.balance | currency }}</mat-option>
              }
            </mat-select></mat-form-field>
            <mat-form-field><mat-label>NCF del suplidor ({{ f.tipo === 'CREDITO' ? 'B04 / E34' : 'B03 / E33' }})</mat-label>
              <input matInput [(ngModel)]="f.ncf" maxlength="13" /></mat-form-field>
            <app-fecha etiqueta="Fecha" [(ngModel)]="f.fecha" />
            <mat-form-field class="ancho-completo"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="f.concepto" maxlength="200" /></mat-form-field>
            @if (f.tipo === 'CREDITO') {
              <mat-checkbox class="ancho-completo" [(ngModel)]="f.devolucion">Devolución de mercancía (sale del inventario)</mat-checkbox>
            }
          </div>
          @if (f.tipo === 'CREDITO' && f.devolucion && compra(); as c) {
            <table class="tabla">
              <thead><tr><th>Artículo</th><th class="num">Comprado</th><th class="num">Costo</th><th class="num">A devolver</th></tr></thead>
              <tbody>@for (l of c.lineas; track l.id) {
                <tr><td>{{ nombres()[l.articuloId!] ?? l.articuloId }}</td><td class="num">{{ l.cantidad | number: '1.0-3' }}</td>
                  <td class="num">{{ l.costoUnitario | currency }}</td>
                  <td class="num"><input class="celda" type="number" min="0" [max]="l.cantidad ?? 0" [ngModel]="devolver()[l.id!] || null"
                    (ngModelChange)="fijarDevolver(l.id!, +$event || 0)" [attr.aria-label]="'Cantidad a devolver ' + (nombres()[l.articuloId!] ?? '')" /></td></tr>
              }</tbody>
            </table>
            <p>Total estimado: <strong>{{ totalDevolucion() | currency }}</strong> (el sistema prorratea el importe e ITBIS de la compra)</p>
          } @else {
            <div class="fila">
              <mat-form-field><mat-label>Monto sin ITBIS</mat-label><input matInput type="number" min="0" [(ngModel)]="f.subtotal" /></mat-form-field>
              <mat-form-field><mat-label>ITBIS</mat-label><input matInput type="number" min="0" [(ngModel)]="f.itbis" /></mat-form-field>
              <button mat-button (click)="f.itbis = redondear(f.subtotal * 0.18)">ITBIS 18%</button>
              <span>Total: <strong>{{ (f.subtotal || 0) + (f.itbis || 0) | currency }}</strong></span>
            </div>
          }
          <div class="fila"><span class="espaciador"></span>
            <button mat-button (click)="nuevo.set(false)">Cancelar</button>
            <button mat-flat-button [disabled]="!valido()" (click)="guardar()">Registrar nota</button></div>
        </div>
      }

      <div class="tarjeta">
        <table class="tabla">
          <thead><tr><th>No.</th><th>Fecha</th><th>Tipo</th><th>NCF</th><th>Compra</th><th>Concepto</th><th class="num">Total</th><th>Estado</th><th></th></tr></thead>
          <tbody>
            @for (n of pagina()?.content ?? []; track n.id) {
              <tr [class.anulado]="n.estado === 'ANULADA'"><td>{{ n.numero }}</td><td>{{ n.fecha | date: 'dd/MM/yyyy' }}</td>
                <td>{{ n.tipo === 'CREDITO' ? 'Crédito' : 'Débito' }}@if (n.devuelveInventario) { <span class="chip">Devolución</span> }</td>
                <td>{{ n.ncf }}</td><td>{{ n.compraId }}</td><td>{{ n.concepto }}</td><td class="num">{{ n.total | currency }}</td>
                <td><span class="chip" [class.ok]="n.estado === 'EMITIDA'" [title]="n.motivoAnulacion ?? ''">{{ n.estado === 'EMITIDA' ? 'Vigente' : 'Anulada' }}</span></td>
                <td>@if (n.estado === 'EMITIDA') { <button mat-button (click)="anular(n)">Anular</button> }</td></tr>
            } @empty { <tr><td colspan="9">Sin notas registradas.</td></tr> }
          </tbody>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
      </div>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .celda { width: 100px; padding: 6px; text-align: right; } .anulado td { text-decoration: line-through; opacity: .6; }
    .chip { margin-left: 6px; }`,
})
export class Notas {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly redondear = redondear;
  protected readonly pagina = signal<Pagina<Nota> | null>(null);
  protected readonly nuevo = signal(false);
  protected readonly proveedores = signal<S['ProveedorResponse'][]>([]);
  protected readonly compras = signal<Compra[]>([]);
  protected readonly compra = signal<Compra | null>(null);
  protected readonly nombres = signal<Record<number, string>>({});
  protected readonly devolver = signal<Record<number, number>>({});
  protected f = this.vacio();
  protected readonly totalDevolucion = computed(() => {
    const c = this.compra();
    if (!c) return 0;
    return redondear((c.lineas ?? []).reduce((s, l) => {
      const q = this.devolver()[l.id!] ?? 0;
      return s + (q > 0 ? ((l.importe ?? 0) + (l.itbis ?? 0)) * q / (l.cantidad ?? 1) : 0);
    }, 0));
  });

  constructor() {
    this.http.get<Pagina<S['ProveedorResponse']>>('/api/v1/catalogos/proveedores', { params: params({ size: 500, sort: 'nombre' }) })
      .subscribe((p) => this.proveedores.set(p.content.filter((x) => x.estado === 'A')));
    this.cargar();
  }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Nota>>('/api/v1/cxp/notas', { params: params({ page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected abrir(): void {
    this.f = this.vacio();
    this.compra.set(null); this.compras.set([]); this.devolver.set({});
    this.nuevo.set(true);
  }

  protected cargarCompras(): void {
    this.compra.set(null);
    this.http.get<Pagina<Compra>>('/api/v1/compras', { params: params({ proveedorId: this.f.proveedorId, size: 50 }) })
      .subscribe((p) => this.compras.set(p.content.filter((c) => c.estado !== 'ANULADA')));
  }

  protected elegirCompra(id: number): void {
    const c = this.compras().find((x) => x.id === id) ?? null;
    this.compra.set(c);
    this.devolver.set({});
    for (const l of c?.lineas ?? []) {
      if (!this.nombres()[l.articuloId!]) this.http.get<S['ArticuloResponse']>(`/api/v1/inventario/articulos/${l.articuloId}`)
        .subscribe((a) => this.nombres.set({ ...this.nombres(), [a.id!]: a.nombre! }));
    }
  }

  protected fijarDevolver(lineaId: number, v: number): void { this.devolver.set({ ...this.devolver(), [lineaId]: Math.max(0, v) }); }

  protected valido(): boolean {
    if (!this.compra() || !this.f.concepto.trim()) return false;
    if (this.f.tipo === 'CREDITO' && this.f.devolucion) return Object.values(this.devolver()).some((q) => q > 0);
    return (this.f.subtotal || 0) + (this.f.itbis || 0) > 0;
  }

  protected guardar(): void {
    const devolucion = this.f.tipo === 'CREDITO' && this.f.devolucion;
    const req: S['NotaProveedorRequest'] = { tipo: this.f.tipo, compraId: this.compra()!.id!, fecha: this.f.fecha || undefined,
      ncf: this.f.ncf.trim() || undefined, concepto: this.f.concepto.trim(),
      subtotal: devolucion ? undefined : this.f.subtotal, itbis: devolucion ? undefined : this.f.itbis,
      lineas: devolucion ? Object.entries(this.devolver()).filter(([, q]) => q > 0).map(([id, q]) => ({ compraLineaId: +id, cantidad: q })) : undefined };
    this.http.post<Nota>('/api/v1/cxp/notas', req).subscribe({
      next: (n) => { this.notif.ok(`Nota ${n.numero} registrada. Balance de la compra: RD$${n.balanceCompra}`); this.nuevo.set(false); this.cargar(); },
      error: (e) => this.notif.error(e) });
  }

  protected anular(n: Nota): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular nota ${n.numero}`, accion: 'Anular', peligro: true, pedirMotivo: 'Motivo',
      mensaje: n.tipo === 'CREDITO' ? 'La deuda con el suplidor vuelve a aumentar' + (n.devuelveInventario ? ' y la mercancía reingresa al inventario.' : '.')
        : 'La deuda con el suplidor se reduce (solo si la nota no se ha pagado).' } })
      .afterClosed().subscribe((motivo) => motivo && this.http.post(`/api/v1/cxp/notas/${n.id}/anular`, { motivo }).subscribe({
        next: () => { this.notif.ok('Nota anulada'); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }

  private vacio() {
    return { tipo: 'CREDITO' as 'CREDITO' | 'DEBITO', proveedorId: null as number | null, ncf: '', fecha: '', concepto: '',
      devolucion: true, subtotal: 0, itbis: 0 };
  }
}
