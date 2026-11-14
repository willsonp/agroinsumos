import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
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
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ClienteSelector } from '../../shared/cliente-selector';
import { ApartadoCancelarDialog, CancelarApartadoResultado } from './apartado-cancelar-dialog';
import { ApartadoAcuse } from './apartado-acuse';
import { exportarExcel, subtitulosEmpresa } from '../../shared/excel';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ESCANEADOS_EMPAQUE, LineaCarrito, TipoPrecio, calcular } from '../../shared/calculo';
import { PresentacionSelector } from '../../shared/presentacion-selector';
import { params, redondear, traerTodo } from '../../shared/http';
import { TicketDialog } from './ticket-dialog';

type Apartado = S['ApartadoResponse'];
type Forma = 'EFECTIVO' | 'TARJETA' | 'CHEQUE' | 'TRANSFERENCIA';

/** Apartados: el cliente separa mercancía y la abona; al completar se factura con el anticipo (legacy apartado). */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-apartados',
  imports: [Fecha, PresentacionSelector, CurrencyPipe, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatPaginatorModule, MatTooltipModule, ArticuloBuscador, ClienteSelector],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Apartados</h1>
        <mat-form-field style="width:180px"><mat-label>Estado</mat-label>
          <mat-select [(ngModel)]="estado" (selectionChange)="cargar()"><mat-option [value]="null">Todos</mat-option>
            <mat-option value="ABIERTO">Abiertos</mat-option><mat-option value="FACTURADO">Facturados</mat-option>
            <mat-option value="CANCELADO">Cancelados</mat-option></mat-select></mat-form-field>
        <span class="espaciador"></span>
        @if (!nuevo()) {
          <button mat-stroked-button [disabled]="exportando()" (click)="excel()"><span class="material-symbols-outlined">table_view</span> Excel</button>
          <button mat-flat-button (click)="abrir()"><span class="material-symbols-outlined">add</span> Nuevo apartado</button>
        }
      </div>
      <p class="muted">Los abonos en efectivo, tarjeta o cheque entran por su caja abierta y quedan como anticipo del cliente.</p>

      @if (nuevo()) {
        <div class="tarjeta">
          <div class="fila">
            <div style="flex:1 1 320px"><app-cliente-selector [(cliente)]="cliente" /></div>
            <mat-form-field style="flex:1 1 260px"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="concepto" maxlength="255" /></mat-form-field>
            <app-fecha etiqueta="Retirar a más tardar" [(ngModel)]="fechaLimite" />
          </div>
          <app-articulo-buscador (elegido)="agregar($event)" />
          <table class="tabla">
            <tr><th>Artículo</th><th class="num">Cantidad</th><th>Unidad</th><th class="num">Desc. %</th><th></th></tr>
            @for (l of lineas(); track $index; let i = $index) {
              <tr><td>{{ l.articulo.nombre }}</td>
                <td class="num"><input class="celda" type="number" min="0" [ngModel]="l.cantidad" (ngModelChange)="fijar(i, 'cantidad', +$event)" /></td>
                <td><app-presentacion [articulo]="l.articulo" [empaque]="!!l.empaque" (cambio)="cambiarUnidad(i, $event)" /></td>
                <td class="num"><input class="celda" type="number" min="0" [ngModel]="l.descuentoPorcentaje" (ngModelChange)="fijar(i, 'descuentoPorcentaje', +$event)" /></td>
                <td><button mat-icon-button (click)="quitar(i)" aria-label="Quitar"><span class="material-symbols-outlined">delete</span></button></td></tr>
            } @empty { <tr><td colspan="5" class="muted">Agregue la mercancía que el cliente separa</td></tr> }
          </table>
          <div class="fila" style="margin-top:12px">
            <mat-form-field><mat-label>Primer abono</mat-label><input matInput type="number" min="0" [(ngModel)]="abono.monto" /></mat-form-field>
            <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="abono.forma">
              @for (f of formas; track f[0]) { <mat-option [value]="f[0]">{{ f[1] }}</mat-option> }</mat-select></mat-form-field>
            <span class="espaciador"></span><span class="total">{{ totales().total | currency }}</span>
            <button mat-button (click)="nuevo.set(false)">Cancelar</button>
            <button mat-flat-button [disabled]="!cliente() || !lineas().length || !concepto.trim() || abono.monto > totales().total" (click)="crear()">Registrar apartado</button>
          </div>
        </div>
      }

      <section class="tarjeta tabla-contenedor">
        <table class="tabla">
          <thead><tr><th>No.</th><th>Fecha</th><th>Cliente</th><th>Concepto</th><th class="num">Total</th><th class="num">Abonado</th><th class="num">Pendiente</th><th>Estado</th><th></th></tr></thead>
          <tbody>
            @for (a of pagina()?.content ?? []; track a.id) {
              <tr class="clickable" (click)="visto.set(visto()?.id === a.id ? null : a)">
                <td>{{ a.numero }}</td><td>{{ a.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ a.cliente }}</td><td>{{ a.concepto }}</td>
                <td class="num">{{ a.total | currency }}</td><td class="num">{{ a.abonado | currency }}</td><td class="num">{{ a.pendiente | currency }}</td>
                <td><span class="chip" [class.ok]="a.estado === 'FACTURADO'" [class.error]="a.estado === 'CANCELADO'" [class.info]="a.estado === 'ABIERTO'">{{ a.estado }}</span>
                  @if (a.estado === 'ABIERTO' && a.fechaLimite && a.fechaLimite < hoy) { <span class="chip error">Vencido</span> }</td>
                <td style="white-space:nowrap" (click)="$event.stopPropagation()">
                  <button mat-icon-button (click)="acuse(a)" matTooltip="Imprimir acuse del apartado" aria-label="Imprimir acuse del apartado">
                    <span class="material-symbols-outlined">print</span></button>
                  @if (a.estado === 'ABIERTO') {
                    @if ((a.pendiente ?? 0) > 0) { <button mat-button (click)="abrirAbono(a)">Abonar</button> }
                    @else { <button mat-flat-button (click)="facturar(a)">Facturar y entregar</button> }
                    @if (auth.esSupervisor()) { <button mat-button (click)="cancelar(a)">Cancelar</button> }
                  }
                </td>
              </tr>
              @if (abonando()?.id === a.id) {
                <tr class="detalle"><td colspan="9"><div class="fila">
                  <mat-form-field><mat-label>Monto (pendiente {{ a.pendiente | currency }})</mat-label><input matInput type="number" min="0" [max]="a.pendiente ?? 0" [(ngModel)]="nuevoAbono.monto" /></mat-form-field>
                  <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="nuevoAbono.forma">
                    @for (f of formas; track f[0]) { <mat-option [value]="f[0]">{{ f[1] }}</mat-option> }</mat-select></mat-form-field>
                  <mat-form-field><mat-label>Referencia</mat-label><input matInput [(ngModel)]="nuevoAbono.referencia" /></mat-form-field>
                  <button mat-button (click)="abonando.set(null)">Cerrar</button>
                  <button mat-flat-button [disabled]="nuevoAbono.monto <= 0 || nuevoAbono.monto > (a.pendiente ?? 0)" (click)="abonar(a)">Registrar abono</button>
                </div></td></tr>
              }
              @if (visto()?.id === a.id) {
                <tr class="detalle"><td colspan="9">
                  <strong>Mercancía:</strong> @for (l of a.lineas; track l.linea) { {{ l.cantidad | number: '1.0-3' }} × {{ l.descripcion }}@if (!$last) { · } }
                  <br /><strong>Abonos:</strong> @for (b of a.abonos; track b.id) { {{ b.fecha | date: 'dd/MM' }} {{ b.tipo === 'DEVOLUCION' ? 'devolución' : b.forma?.toLowerCase() }} {{ b.monto | currency }}
                    @if (b.tipo !== 'DEVOLUCION') { <button mat-icon-button class="mini" (click)="acuse(a, b.id)" matTooltip="Imprimir acuse de este abono" aria-label="Imprimir acuse del abono">
                      <span class="material-symbols-outlined">receipt</span></button> }@if (!$last) { · } } @empty { ninguno }
                  @if (a.motivoCancelacion) { <br /><span class="error-texto">Cancelado: {{ a.motivoCancelacion }}</span> }
                </td></tr>
              }
            } @empty { <tr><td colspan="9" class="muted">Sin apartados</td></tr> }
          </tbody>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
      </section>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .celda { width: 90px; padding: 6px; text-align: right; } .clickable { cursor: pointer; }
    .detalle td { background: var(--mat-sys-surface-container-low); } .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); }
    .chip { margin-right: 4px; } section { margin-top: 16px; }
    .mini { --mat-icon-button-state-layer-size: 28px; padding: 2px; vertical-align: middle; }`,
})
export class Apartados {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly formas: [Forma, string][] = [['EFECTIVO', 'Efectivo'], ['TARJETA', 'Tarjeta'], ['CHEQUE', 'Cheque'], ['TRANSFERENCIA', 'Transferencia']];
  protected readonly hoy = new Date().toISOString().slice(0, 10);
  protected readonly pagina = signal<Pagina<Apartado> | null>(null);
  protected readonly nuevo = signal(false);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly lineas = signal<LineaCarrito[]>([]);
  protected readonly visto = signal<Apartado | null>(null);
  protected readonly abonando = signal<Apartado | null>(null);
  protected estado: string | null = 'ABIERTO';
  protected concepto = '';
  protected fechaLimite = '';
  protected abono = { monto: 0, forma: 'EFECTIVO' as Forma };
  protected nuevoAbono = { monto: 0, forma: 'EFECTIVO' as Forma, referencia: '' };
  protected readonly totales = computed(() => calcular(this.lineas(), (this.cliente()?.tipoPrecio as TipoPrecio) ?? 'CONTADO', !!this.cliente()?.exentoItbis));

  constructor() { this.cargar(); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Apartado>>('/api/v1/ventas/apartados', { params: params({ estado: this.estado, page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected abrir(): void {
    this.lineas.set([]); this.cliente.set(null); this.concepto = ''; this.fechaLimite = ''; this.abono = { monto: 0, forma: 'EFECTIVO' };
    this.nuevo.set(true);
  }
  protected agregar(a: S['ArticuloResponse']): void {
    this.lineas.set([...this.lineas(), { articulo: a, cantidad: 1, descuentoPorcentaje: 0, empaque: ESCANEADOS_EMPAQUE.has(a) }]);
  }
  protected cambiarUnidad(i: number, empaque: boolean): void { this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, empaque } : l))); }
  protected fijar(i: number, campo: 'cantidad' | 'descuentoPorcentaje', v: number): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: Math.max(0, v || 0) } : l)));
  }
  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }

  protected crear(): void {
    const req: S['ApartadoRequest'] = { clienteId: this.cliente()!.id!, concepto: this.concepto.trim(), fechaLimite: this.fechaLimite || undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad, descuentoPorcentaje: l.descuentoPorcentaje || undefined,
        empaque: l.empaque || undefined })),
      abonoInicial: this.abono.monto > 0 ? { forma: this.abono.forma, monto: redondear(this.abono.monto) } : undefined };
    this.http.post<Apartado>('/api/v1/ventas/apartados', req).subscribe({
      next: (a) => {
        this.notif.ok(`Apartado ${a.numero} registrado · pendiente RD$${a.pendiente}`); this.nuevo.set(false); this.cargar();
        this.acuse(a);   // acuse para el cliente: mercancía separada y primer abono
      },
      error: (e) => this.notif.error(e) });
  }

  protected abrirAbono(a: Apartado): void { this.nuevoAbono = { monto: a.pendiente ?? 0, forma: 'EFECTIVO', referencia: '' }; this.abonando.set(a); }

  protected abonar(a: Apartado): void {
    this.http.post<Apartado>(`/api/v1/ventas/apartados/${a.id}/abonos`, { forma: this.nuevoAbono.forma, monto: redondear(this.nuevoAbono.monto),
      referencia: this.nuevoAbono.referencia || undefined }).subscribe({
      next: (r) => {
        this.notif.ok(`Abono registrado · pendiente RD$${r.pendiente}`); this.abonando.set(null); this.cargar();
        const ultimo = (r.abonos ?? []).filter((b) => b.tipo !== 'DEVOLUCION').at(-1);
        this.acuse(r, ultimo?.id);
      },
      error: (e) => this.notif.error(e) });
  }

  protected facturar(a: Apartado): void {
    this.http.post<S['FacturaResponse']>(`/api/v1/ventas/apartados/${a.id}/facturar`, {}).subscribe({
      next: (f) => { this.notif.ok(`Factura ${f.numero} emitida y pagada con los abonos`); this.cargar(); this.dialog.open(TicketDialog, { data: { factura: f } }); },
      error: (e) => this.notif.error(e) });
  }

  protected acuse(a: Apartado, abonoId?: number): void { this.dialog.open(ApartadoAcuse, { data: { apartado: a, abonoId } }); }

  protected readonly exportando = signal(false);

  /** Todos los apartados del filtro actual (no solo la página visible). */
  protected excel(): void {
    this.exportando.set(true);
    const nombres: Record<string, string> = { ABIERTO: 'Abierto', FACTURADO: 'Facturado', CANCELADO: 'Cancelado' };
    traerTodo<Apartado>(this.http, '/api/v1/ventas/apartados', { estado: this.estado }).subscribe({
      next: (as) => {
        this.exportando.set(false);
        const suma = (f: (a: Apartado) => number | undefined) => as.reduce((s, a) => s + (f(a) ?? 0), 0);
        exportarExcel({
          archivo: 'apartados', titulo: 'Apartados',
          subtitulos: subtitulosEmpresa(this.auth.perfil()?.compania, 'Estado: ' + (this.estado ? nombres[this.estado] : 'Todos')),
          columnas: [{ titulo: 'No.', tipo: 'numero' }, { titulo: 'Fecha', tipo: 'fecha' }, { titulo: 'Cliente' }, { titulo: 'Concepto' },
            { titulo: 'Mercancía' }, { titulo: 'Total', tipo: 'moneda' }, { titulo: 'Abonado', tipo: 'moneda' }, { titulo: 'Pendiente', tipo: 'moneda' },
            { titulo: 'Retirar a más tardar', tipo: 'fecha' }, { titulo: 'Estado' }, { titulo: 'Motivo de cancelación' }],
          filas: as.map((a) => [a.numero, a.fecha, a.cliente, a.concepto,
            (a.lineas ?? []).map((l) => `${l.cantidad} x ${l.descripcion}`).join('; '), a.total, a.abonado, a.pendiente,
            a.fechaLimite ?? null, nombres[a.estado ?? ''] ?? a.estado, a.motivoCancelacion ?? '']),
          totales: ['TOTAL', null, as.length + ' apartado(s)', null, null, suma((a) => a.total), suma((a) => a.abonado), suma((a) => a.pendiente),
            null, null, null],
        });
      },
      error: (e) => { this.exportando.set(false); this.notif.error(e); },
    });
  }

  protected cancelar(a: Apartado): void {
    this.dialog.open(ApartadoCancelarDialog, { data: { numero: a.numero, cliente: a.cliente, abonado: a.abonado ?? 0 }, width: 'min(680px, 96vw)', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: CancelarApartadoResultado) => {
        if (!r) return;   // "No cancelar"
        this.http.post(`/api/v1/ventas/apartados/${a.id}/cancelar`, r).subscribe({
          next: () => {
            this.notif.ok(r.devolverDinero ? `Apartado cancelado: se devolvieron RD$${a.abonado} desde su caja`
              : (a.abonado ?? 0) > 0 ? `Apartado cancelado: RD$${a.abonado} quedan como saldo a favor del cliente` : 'Apartado cancelado');
            this.cargar();
          },
          error: (e) => this.notif.error(e),
        });
      });
  }
}
