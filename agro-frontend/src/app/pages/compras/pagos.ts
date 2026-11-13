import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { Pagina, S } from '../../api/tipos';
import { params, redondear } from '../../shared/http';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { Notificacion } from '../../core/notificacion.service';
import { PagoProveedorDialog } from './compras';
import { ComprobantePagoDialog } from './comprobante-pago-dialog';

type Pendiente = S['PagoProveedorCompraPendiente'];
interface Abono { doc: Pendiente; monto: number; }

/**
 * Cuentas por pagar (legacy módulo CxP): pagar o abonar varias compras de un suplidor en un solo pago, distribuir un
 * monto (de la compra que vence primero a la más reciente), comprobante de pago impreso, pagos realizados y
 * antigüedad de saldos con suplidores.
 */
@Component({
  selector: 'app-pagos-suplidores',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule,
    MatTooltipModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Pagos a suplidores</h1></div>
      <mat-tab-group>
        <mat-tab label="Pagar">
          <div class="tarjeta">
            <mat-form-field style="width:100%"><mat-label>Suplidor (con balance pendiente)</mat-label>
              <mat-select [value]="proveedorId()" (selectionChange)="elegir($event.value)">
                @for (a of antiguedad(); track a.proveedorId) {
                  <mat-option [value]="a.proveedorId">{{ a.proveedor }} · debe {{ a.total | currency }}</mat-option>
                } @empty { <mat-option disabled>No hay compras pendientes de pago</mat-option> }
              </mat-select></mat-form-field>
            @if (proveedorId()) {
              <div class="tabla-contenedor"><table class="tabla">
                <tr><th>Compra</th><th>NCF</th><th>Fecha</th><th>Vence</th><th class="num">Balance</th><th class="num">Pagar</th></tr>
                @for (a of abonos(); track a.doc.id; let i = $index) {
                  <tr><td>{{ a.doc.numero }}</td><td>{{ a.doc.ncfProveedor }}</td><td>{{ a.doc.fecha | date: 'dd/MM/yyyy' }}</td>
                    <td [class.vencido]="(a.doc.diasVencido ?? 0) > 0">{{ a.doc.vencimiento | date: 'dd/MM/yyyy' }}
                      @if ((a.doc.diasVencido ?? 0) > 0) { ({{ a.doc.diasVencido }} días) }</td>
                    <td class="num">{{ balance(a.doc) | currency: a.doc.moneda }}</td>
                    <td class="num"><input class="celda" type="number" min="0" [ngModel]="a.monto" (ngModelChange)="fijar(i, +$event)"
                      [attr.aria-label]="'Pagar compra ' + a.doc.numero" /></td></tr>
                } @empty { <tr><td colspan="6" class="muted">El suplidor no tiene compras pendientes</td></tr> }
              </table></div>
              @if (abonos().length) {
                <div class="pie">
                  <mat-form-field style="flex:0 1 200px"><mat-label>Monto a pagar</mat-label>
                    <input matInput type="number" min="0" [(ngModel)]="montoDistribuir" (keydown.enter)="distribuir()" /></mat-form-field>
                  <button mat-stroked-button (click)="distribuir()"><span class="material-symbols-outlined">call_split</span> Distribuir monto</button>
                  <button mat-stroked-button (click)="saldarTodo()">Saldar todo</button>
                  <mat-form-field style="flex:1 1 240px"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="concepto" maxlength="200" /></mat-form-field>
                  <div class="total">{{ total() | currency: moneda() }}</div>
                  <button mat-flat-button [disabled]="excede() || total() <= 0 || variasMonedas()" (click)="pagar()">Pagar</button>
                </div>
                @if (excede()) { <p class="error-texto">Hay montos mayores al balance de la compra</p> }
                @if (variasMonedas()) { <p class="error-texto">Pague por separado las compras en otra moneda</p> }
              }
            }
          </div>
        </mat-tab>

        <mat-tab label="Pagos realizados">
          <div class="tarjeta">
            <div class="tabla-contenedor"><table class="tabla">
              <tr><th>No.</th><th>Fecha</th><th>Suplidor</th><th>Concepto</th><th class="num">Monto</th><th>Estado</th><th></th></tr>
              @for (p of pagos(); track p.id) {
                <tr><td>{{ p.numero }}</td><td>{{ p.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ nombreProveedor(p.proveedorId) }}</td><td>{{ p.concepto }}</td>
                  <td class="num">{{ p.total | currency: p.moneda }}</td>
                  <td><span class="chip" [class.error]="p.estado === 'ANULADO'">{{ p.estado }}</span></td>
                  <td><button mat-icon-button (click)="imprimir(p.id!)" matTooltip="Imprimir comprobante" aria-label="Imprimir comprobante">
                      <span class="material-symbols-outlined">print</span></button>
                    @if (p.estado !== 'ANULADO') {
                      <button mat-icon-button (click)="anular(p)" matTooltip="Anular pago" aria-label="Anular pago"><span class="material-symbols-outlined">block</span></button>
                    }</td></tr>
              } @empty { <tr><td colspan="7" class="muted">Sin pagos registrados</td></tr> }
            </table></div>
          </div>
        </mat-tab>

        <mat-tab label="Antigüedad de saldos">
          <div class="tarjeta">
            <div class="tabla-contenedor"><table class="tabla">
              <tr><th>Suplidor</th><th class="num">Corriente</th><th class="num">1-30 días</th><th class="num">31-60</th><th class="num">61-90</th>
                <th class="num">Más de 90</th><th class="num">Total</th></tr>
              @for (a of antiguedad(); track a.proveedorId) {
                <tr class="clickable" (click)="elegir(a.proveedorId!)"><td>{{ a.proveedor }}</td><td class="num">{{ a.corriente | currency }}</td>
                  <td class="num">{{ a.d1a30 | currency }}</td><td class="num">{{ a.d31a60 | currency }}</td><td class="num">{{ a.d61a90 | currency }}</td>
                  <td class="num" [class.vencido]="(a.mas90 ?? 0) > 0">{{ a.mas90 | currency }}</td><td class="num"><strong>{{ a.total | currency }}</strong></td></tr>
              } @empty { <tr><td colspan="7" class="muted">No se le debe a ningún suplidor</td></tr> }
              @if (antiguedad().length) {
                <tr class="final"><td>TOTAL</td><td class="num">{{ suma('corriente') | currency }}</td><td class="num">{{ suma('d1a30') | currency }}</td>
                  <td class="num">{{ suma('d31a60') | currency }}</td><td class="num">{{ suma('d61a90') | currency }}</td>
                  <td class="num">{{ suma('mas90') | currency }}</td><td class="num"><strong>{{ suma('total') | currency }}</strong></td></tr>
              }
            </table></div>
            <p class="muted pequeno">Días vencidos según la fecha de vencimiento de cada compra. Toque un suplidor para pagarle.</p>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 110px; padding: 6px; text-align: right; } .vencido { color: var(--mat-sys-error); }
    .pie { display: flex; gap: 16px; align-items: center; margin-top: 16px; flex-wrap: wrap; }
    .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); }
    .final td { font-weight: 600; } .clickable { cursor: pointer; }`,
})
export class PagosSuplidores {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly antiguedad = signal<S['PagoProveedorAntiguedad'][]>([]);
  protected readonly pagos = signal<S['PagoProveedorResponse'][]>([]);
  protected readonly proveedorId = signal<number | null>(null);
  protected readonly abonos = signal<Abono[]>([]);
  protected montoDistribuir: number | null = null;
  protected concepto = '';
  private nombres: Record<number, string> = {};

  protected readonly total = computed(() => redondear(this.abonos().reduce((s, a) => s + a.monto, 0)));
  protected readonly excede = computed(() => this.abonos().some((a) => a.monto > this.balance(a.doc) + 0.001));
  private readonly elegidos = computed(() => this.abonos().filter((a) => a.monto > 0));
  protected readonly variasMonedas = computed(() => new Set(this.elegidos().map((a) => a.doc.moneda)).size > 1);
  protected readonly moneda = computed(() => this.elegidos()[0]?.doc.moneda ?? 'DOP');

  constructor() {
    this.cargar();
  }

  private cargar(): void {
    this.http.get<S['PagoProveedorAntiguedad'][]>('/api/v1/cxp/antiguedad').subscribe((a) => {
      this.antiguedad.set(a);
      for (const x of a) this.nombres[x.proveedorId!] = x.proveedor!;
    });
    this.http.get<Pagina<S['PagoProveedorResponse']>>('/api/v1/cxp/pagos', { params: params({ size: 50 }) })
      .subscribe((p) => this.pagos.set(p.content));
    this.http.get<Pagina<S['ProveedorResponse']>>('/api/v1/catalogos/proveedores', { params: params({ size: 500 }) })
      .subscribe((p) => { for (const x of p.content) this.nombres[x.id!] ??= x.nombre!; });
  }

  protected nombreProveedor(id?: number): string { return this.nombres[id ?? 0] ?? `#${id}`; }

  /** Balance en la moneda de la compra. */
  protected balance(d: Pendiente): number { return (d.moneda !== 'DOP' ? d.balanceMoneda : d.balance) ?? 0; }

  protected suma(campo: 'corriente' | 'd1a30' | 'd31a60' | 'd61a90' | 'mas90' | 'total'): number {
    return redondear(this.antiguedad().reduce((s, a) => s + (a[campo] ?? 0), 0));
  }

  protected elegir(id: number): void {
    this.proveedorId.set(id);
    this.abonos.set([]);
    this.montoDistribuir = null;
    this.http.get<Pendiente[]>(`/api/v1/cxp/proveedores/${id}/pendientes`).subscribe({
      next: (docs) => this.abonos.set(docs.map((doc) => ({ doc, monto: 0 }))),
      error: (e) => this.notif.error(e),
    });
  }

  protected fijar(i: number, v: number): void {
    this.abonos.set(this.abonos().map((a, j) => (j === i ? { ...a, monto: Math.max(0, v || 0) } : a)));
  }

  protected saldarTodo(): void {
    const m = this.abonos()[0]?.doc.moneda;   // una moneda por pago
    this.abonos.set(this.abonos().map((a) => ({ ...a, monto: a.doc.moneda === m ? this.balance(a.doc) : 0 })));
  }

  /** Reparte el monto de la compra que vence primero a la más reciente, saldando las que alcance. */
  protected distribuir(): void {
    let restante = redondear(this.montoDistribuir ?? 0);
    if (restante <= 0) { this.notif.error(null, 'Indique el monto a distribuir'); return; }
    const m = this.abonos()[0]?.doc.moneda;
    const orden = this.abonos().map((a, i) => ({ i, clave: a.doc.vencimiento ?? a.doc.fecha ?? '' }))
      .sort((x, y) => x.clave.localeCompare(y.clave) || x.i - y.i).map((x) => x.i);
    const montos = new Map<number, number>();
    for (const i of orden) {
      const a = this.abonos()[i];
      if (a.doc.moneda !== m) continue;
      const pago = Math.min(restante, this.balance(a.doc));
      montos.set(i, pago);
      restante = redondear(restante - pago);
    }
    this.abonos.set(this.abonos().map((a, i) => ({ ...a, monto: montos.get(i) ?? 0 })));
    if (restante > 0) this.notif.error(null, `El monto supera la deuda en ${restante.toFixed(2)}: se aplicó solo lo pendiente`);
  }

  protected pagar(): void {
    const elegidos = this.elegidos();
    const moneda = this.moneda();
    const ext = moneda !== 'DOP';
    const total = this.total();
    // El diálogo de forma de pago de Compras, con el total fijo (cuenta bancaria, cheque, efectivo de la caja, tasa)
    const data = { titulo: `Pagar ${elegidos.length} compra(s) a ${this.nombreProveedor(this.proveedorId()!)}`, montoFijo: true,
      numero: 0, moneda, balance: ext ? undefined : total, balanceMoneda: ext ? total : undefined,
      tasaCambio: ext ? elegidos[0].doc.balance! / (elegidos[0].doc.balanceMoneda || 1) : 1 };
    this.dialog.open(PagoProveedorDialog, { data }).afterClosed().subscribe((r?: { forma: S['PagoRequest']; tasa: number | null }) => {
      if (!r) return;
      const req: S['PagoProveedorRequest'] = { proveedorId: this.proveedorId()!, concepto: this.concepto || undefined,
        aplicaciones: elegidos.map((a) => ({ compraId: a.doc.id!, monto: a.monto })), formas: [{ ...r.forma, monto: total }],
        moneda, tasaCambio: ext ? r.tasa ?? undefined : undefined };
      this.http.post<S['PagoProveedorResponse']>('/api/v1/cxp/pagos', req).subscribe({
        next: (p) => {
          this.notif.ok(`Pago ${p.numero} registrado`);
          this.concepto = '';
          this.imprimir(p.id!);   // comprobante con lo que quedó debiéndose
          const id = this.proveedorId()!;
          this.cargar();
          this.elegir(id);
        },
        error: (e) => this.notif.error(e),
      });
    });
  }

  protected imprimir(pagoId: number): void { this.dialog.open(ComprobantePagoDialog, { data: { pagoId } }); }

  protected anular(p: S['PagoProveedorResponse']): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular pago ${p.numero}`, peligro: true, accion: 'Anular',
      pedirMotivo: 'Motivo de la anulación', mensaje: 'Las compras vuelven a quedar pendientes por lo pagado.' } })
      .afterClosed().subscribe((motivo?: string) => {
      if (!motivo) return;
      this.http.post(`/api/v1/cxp/pagos/${p.id}/anular`, { motivo }).subscribe({
        next: () => { this.notif.ok('Pago anulado'); this.cargar(); if (this.proveedorId()) this.elegir(this.proveedorId()!); },
        error: (e) => this.notif.error(e),
      });
    });
  }
}
