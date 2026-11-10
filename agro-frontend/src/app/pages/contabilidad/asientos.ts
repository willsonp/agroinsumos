import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { isoFecha, params, redondear } from '../../shared/http';
import { CuentasContables } from './cuentas-contables';

type Asiento = S['AsientoResponse'];
interface Linea { cuentaId: number | null; descripcion: string; debito: number; credito: number; }

export const ORIGENES_CONTABLES: Record<string, string> = {
  FACTURA: 'Factura', DEVOLUCION: 'Devolución', NOTA_DEBITO: 'Nota de débito', RECIBO: 'Recibo', COMPRA: 'Compra',
  PAGO_PROVEEDOR: 'Pago a suplidor', NOTA_PROVEEDOR: 'Nota de suplidor', MOV_BANCO: 'Banco', EGRESO_CAJA: 'Gasto de caja',
};

/** Diario general: asientos manuales y generación de los automáticos. */
@Component({
  selector: 'app-asientos',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatPaginatorModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Asientos contables</h1><span class="espaciador"></span>
        @if (puedeRegistrar() && !nuevo()) {
          <button mat-stroked-button (click)="generando.set(!generando())"><span class="material-symbols-outlined">auto_mode</span> Asientos automáticos</button>
          <button mat-flat-button (click)="abrirNuevo()"><span class="material-symbols-outlined">add</span> Asiento manual</button>
        }
      </div>

      @if (generando()) {
        <div class="tarjeta">
          <h2>Contabilizar documentos</h2>
          <p class="ayuda">Genera los asientos de facturas, devoluciones, notas de débito, recibos, compras, pagos, banco y gastos de caja
            del rango que aún no estén contabilizados, y reversa los documentos anulados. Puede ejecutarse las veces que haga falta.</p>
          <div class="fila">
            <mat-form-field><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="gen.desde" /></mat-form-field>
            <mat-form-field><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="gen.hasta" /></mat-form-field>
            <button mat-stroked-button (click)="generar(true)">Simular</button>
            <button mat-flat-button (click)="generar(false)">Generar</button>
          </div>
          @if (resultado(); as r) {
            <p><strong>{{ r.simulacion ? 'Simulación' : 'Generación' }}:</strong> {{ r.asientos }} asientos, {{ r.reversos }} reversos.</p>
            @if (r.porOrigen?.length) {
              <table class="tabla"><thead><tr><th>Documento</th><th class="num">Asientos</th><th class="num">Reversos</th><th class="num">Monto</th></tr></thead>
                <tbody>@for (o of r.porOrigen; track o.origenTipo) {
                  <tr><td>{{ o.descripcion }}</td><td class="num">{{ o.asientos }}</td><td class="num">{{ o.reversos }}</td><td class="num">{{ o.monto | currency }}</td></tr>
                }</tbody></table>
            }
            @if (r.errores?.length) {
              <h3 class="error-texto">{{ r.errores!.length }} documento(s) pendiente(s)</h3>
              <table class="tabla"><thead><tr><th>Fecha</th><th>Documento</th><th>Problema</th></tr></thead>
                <tbody>@for (e of r.errores; track $index) {
                  <tr><td>{{ e.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ e.documento }}</td><td class="error-texto">{{ e.mensaje }}</td></tr>
                }</tbody></table>
            }
          }
        </div>
      }

      @if (nuevo()) {
        <div class="tarjeta">
          <h2>Asiento manual</h2>
          <div class="fila">
            <mat-form-field><mat-label>Fecha</mat-label><input matInput type="date" [(ngModel)]="cab.fecha" /></mat-form-field>
            <mat-form-field style="flex:1"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="cab.concepto" maxlength="200" /></mat-form-field>
          </div>
          <table class="tabla lineas">
            <thead><tr><th>Cuenta</th><th>Descripción</th><th class="num">Débito</th><th class="num">Crédito</th><th></th></tr></thead>
            <tbody>
              @for (l of lineas(); track $index; let i = $index) {
                <tr>
                  <td><mat-select [ngModel]="l.cuentaId" (ngModelChange)="fijar(i, 'cuentaId', $event)" placeholder="Seleccione la cuenta" [aria-label]="'Cuenta línea ' + (i + 1)">
                    @for (c of cuentas.detalle(); track c.id) { <mat-option [value]="c.id">{{ c.codigo }} · {{ c.nombre }}</mat-option> }
                  </mat-select></td>
                  <td><input class="celda texto" [ngModel]="l.descripcion" (ngModelChange)="fijar(i, 'descripcion', $event)" /></td>
                  <td><input class="celda" type="number" min="0" [ngModel]="l.debito || null" (ngModelChange)="fijar(i, 'debito', +$event || 0)" [attr.aria-label]="'Débito línea ' + (i + 1)" /></td>
                  <td><input class="celda" type="number" min="0" [ngModel]="l.credito || null" (ngModelChange)="fijar(i, 'credito', +$event || 0)" [attr.aria-label]="'Crédito línea ' + (i + 1)" /></td>
                  <td><button mat-icon-button (click)="quitar(i)" aria-label="Quitar línea"><span class="material-symbols-outlined">delete</span></button></td>
                </tr>
              }
              <tr class="totales"><td><button mat-button (click)="agregar()">+ Línea</button></td><td>Totales</td>
                <td class="num">{{ totales().debito | currency }}</td><td class="num">{{ totales().credito | currency }}</td><td></td></tr>
            </tbody>
          </table>
          @if (totales().diferencia !== 0) { <p class="error-texto">Diferencia: {{ totales().diferencia | currency }}</p> }
          <div class="fila"><span class="espaciador"></span>
            <button mat-button (click)="nuevo.set(false)">Cancelar</button>
            <button mat-flat-button [disabled]="!valido()" (click)="guardar()">Registrar asiento</button></div>
        </div>
      }

      <div class="tarjeta">
        <div class="fila">
          <mat-form-field><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="filtro.desde" (change)="cargar()" /></mat-form-field>
          <mat-form-field><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="filtro.hasta" (change)="cargar()" /></mat-form-field>
          <mat-form-field><mat-label>Origen</mat-label><mat-select [(ngModel)]="filtro.origenTipo" (selectionChange)="cargar()">
            <mat-option [value]="null">Todos</mat-option><mat-option value="MANUAL">Manuales</mat-option>
            @for (o of origenesLista; track o[0]) { <mat-option [value]="o[0]">{{ o[1] }}</mat-option> }
          </mat-select></mat-form-field>
        </div>
        <table class="tabla diario">
          <thead><tr><th>No.</th><th>Fecha</th><th>Concepto</th><th>Origen</th><th class="num">Total</th><th></th></tr></thead>
          <tbody>
            @for (a of pagina()?.content ?? []; track a.id) {
              <tr class="cabecera" (click)="abierto.set(abierto() === a.id ? null : a.id!)" [class.anulado]="a.estado === 'ANULADO'">
                <td>{{ a.numero }}</td><td>{{ a.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ a.concepto }}</td>
                <td>{{ a.tipo === 'MANUAL' ? 'Manual' : (origenes[a.origenTipo!] ?? a.origenTipo) }}@if (a.reverso) { <span class="chip">Reverso</span> }
                  @if (a.estado === 'ANULADO') { <span class="chip" [title]="a.motivoAnulacion ?? ''">Anulado</span> }</td>
                <td class="num">{{ a.total | currency }}</td>
                <td>@if (puedeRegistrar() && a.tipo === 'MANUAL' && a.estado === 'REGISTRADO') {
                  <button mat-button (click)="anular(a); $event.stopPropagation()">Anular</button> }</td>
              </tr>
              @if (abierto() === a.id) {
                @for (l of a.lineas; track l.linea) {
                  <tr class="detalle"><td></td><td colspan="2">{{ l.cuentaCodigo }} · {{ l.cuentaNombre }}<div class="sub">{{ l.descripcion }}</div></td>
                    <td class="num">{{ l.debito ? (l.debito | currency) : '' }}</td><td class="num">{{ l.credito ? (l.credito | currency) : '' }}</td><td></td></tr>
                }
              }
            } @empty { <tr><td colspan="6">Sin asientos en el rango.</td></tr> }
          </tbody>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="50" (page)="cargar($event)" />
      </div>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .cabecera { cursor: pointer; } .detalle td { background: var(--mat-sys-surface-container); font-size: .92em; }
    .sub { color: var(--mat-sys-on-surface-variant); font-size: .85em; } .anulado td { text-decoration: line-through; opacity: .6; }
    .lineas mat-select { min-width: 260px; } .celda { width: 120px; padding: 6px; text-align: right; } .celda.texto { width: 100%; text-align: left; }
    .totales td { font-weight: 600; } .ayuda { color: var(--mat-sys-on-surface-variant); } .chip { margin-left: 6px; }`,
})
export class Asientos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly cuentas = inject(CuentasContables);
  protected readonly origenes = ORIGENES_CONTABLES;
  protected readonly origenesLista = Object.entries(ORIGENES_CONTABLES);
  protected readonly puedeRegistrar = computed(() => this.auth.puede('CONT_ASIENTOS'));
  protected readonly pagina = signal<Pagina<Asiento> | null>(null);
  protected readonly abierto = signal<number | null>(null);
  protected readonly nuevo = signal(false);
  protected readonly generando = signal(false);
  protected readonly resultado = signal<S['GeneracionAsientosResultado'] | null>(null);
  protected readonly lineas = signal<Linea[]>([]);
  private readonly hoy = new Date();
  protected filtro = { desde: isoFecha(new Date(this.hoy.getFullYear(), this.hoy.getMonth(), 1))!, hasta: isoFecha(this.hoy)!, origenTipo: null as string | null };
  protected gen = { desde: this.filtro.desde, hasta: this.filtro.hasta };
  protected cab = { fecha: isoFecha(this.hoy)!, concepto: '' };
  protected readonly totales = computed(() => {
    const d = redondear(this.lineas().reduce((s, l) => s + l.debito, 0));
    const c = redondear(this.lineas().reduce((s, l) => s + l.credito, 0));
    return { debito: d, credito: c, diferencia: redondear(d - c) };
  });

  constructor() {
    this.cuentas.cargar();
    this.cargar();
  }

  protected cargar(e?: PageEvent): void {
    const manual = this.filtro.origenTipo === 'MANUAL';
    this.http.get<Pagina<Asiento>>('/api/v1/contabilidad/asientos', { params: params({ desde: this.filtro.desde, hasta: this.filtro.hasta,
      tipo: manual ? 'MANUAL' : null, origenTipo: manual ? null : this.filtro.origenTipo, page: e?.pageIndex ?? 0, size: 50 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected generar(simular: boolean): void {
    this.http.post<S['GeneracionAsientosResultado']>('/api/v1/contabilidad/asientos/generar', {},
      { params: params({ desde: this.gen.desde, hasta: this.gen.hasta, simular }) }).subscribe({
      next: (r) => {
        this.resultado.set(r);
        if (!simular) { this.notif.ok(`${r.asientos} asientos y ${r.reversos} reversos generados`); this.cargar(); }
      },
      error: (e) => this.notif.error(e),
    });
  }

  protected abrirNuevo(): void {
    this.cab = { fecha: isoFecha(new Date())!, concepto: '' };
    this.lineas.set([this.vacia(), this.vacia()]);
    this.generando.set(false);
    this.nuevo.set(true);
  }

  protected agregar(): void { this.lineas.set([...this.lineas(), this.vacia()]); }
  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }
  protected fijar<K extends keyof Linea>(i: number, k: K, v: Linea[K]): void {
    this.lineas.set(this.lineas().map((l, j) => {
      if (j !== i) return l;
      const n = { ...l, [k]: v };
      // Una línea es débito o crédito
      if (k === 'debito' && Number(v) > 0) n.credito = 0;
      if (k === 'credito' && Number(v) > 0) n.debito = 0;
      return n;
    }));
  }

  protected valido(): boolean {
    const ls = this.lineas();
    return !!this.cab.fecha && !!this.cab.concepto.trim() && ls.length >= 2 && this.totales().diferencia === 0 && this.totales().debito > 0
      && ls.every((l) => l.cuentaId && (l.debito > 0) !== (l.credito > 0));
  }

  protected guardar(): void {
    const req: S['AsientoRequest'] = { fecha: this.cab.fecha, concepto: this.cab.concepto.trim(),
      lineas: this.lineas().map((l) => ({ cuentaId: l.cuentaId!, descripcion: l.descripcion || undefined,
        debito: l.debito ? redondear(l.debito) : undefined, credito: l.credito ? redondear(l.credito) : undefined })) };
    this.http.post<Asiento>('/api/v1/contabilidad/asientos', req).subscribe({
      next: (a) => { this.notif.ok(`Asiento ${a.numero} registrado`); this.nuevo.set(false); this.cargar(); },
      error: (e) => this.notif.error(e) });
  }

  protected anular(a: Asiento): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular asiento ${a.numero}`, mensaje: a.concepto ?? '', accion: 'Anular',
      pedirMotivo: 'Motivo de la anulación', peligro: true } }).afterClosed().subscribe((motivo) => {
      if (!motivo) return;
      this.http.post(`/api/v1/contabilidad/asientos/${a.id}/anular`, { motivo }).subscribe({
        next: () => { this.notif.ok('Asiento anulado'); this.cargar(); }, error: (e) => this.notif.error(e) });
    });
  }

  private vacia(): Linea { return { cuentaId: null, descripcion: '', debito: 0, credito: 0 }; }
}
