import { catchError, map, of, switchMap } from 'rxjs';
import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
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
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { ESCANEADOS_EMPAQUE, LineaCarrito, TipoPrecio, calcular } from '../../shared/calculo';
import { PresentacionSelector } from '../../shared/presentacion-selector';
import { params, redondear } from '../../shared/http';
import { TicketDialog } from './ticket-dialog';
import { PagoDialog } from './pago-dialog';
import { ComprobanteService } from '../../core/comprobante.service';

type Conduce = S['ConduceResponse'];

/** Conduces: la mercancía sale al entregarse y se factura después, uno o varios juntos (legacy facturapro). */
@Component({
  selector: 'app-conduces',
  imports: [PresentacionSelector, CurrencyPipe, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatPaginatorModule, ArticuloBuscador, ClienteSelector],
  template: `
    <div class="pagina">
      <div class="pagina-titulo no-imprimir"><h1>Conduces</h1>
        <mat-form-field style="width:180px"><mat-label>Estado</mat-label>
          <mat-select [(ngModel)]="estado" (selectionChange)="cargar()"><mat-option [value]="null">Todos</mat-option>
            <mat-option value="ABIERTO">Por facturar</mat-option><mat-option value="FACTURADO">Facturados</mat-option>
            <mat-option value="ANULADO">Anulados</mat-option></mat-select></mat-form-field>
        <span class="espaciador"></span>
        @if (!nuevo()) { <button mat-flat-button (click)="abrir()"><span class="material-symbols-outlined">add</span> Nuevo conduce</button> }
      </div>

      @if (nuevo()) {
        <div class="tarjeta no-imprimir">
          <div class="fila">
            <div style="flex:1 1 320px"><app-cliente-selector [(cliente)]="cliente" /></div>
            <mat-form-field style="flex:1 1 260px"><mat-label>Lugar de entrega</mat-label><input matInput [(ngModel)]="lugarEntrega" maxlength="200" /></mat-form-field>
          </div>
          <app-articulo-buscador (elegido)="agregar($event)" />
          <table class="tabla">
            <tr><th>Artículo</th><th class="num">Cantidad</th><th>Unidad</th><th class="num">Desc. %</th><th></th></tr>
            @for (l of lineas(); track $index; let i = $index) {
              <tr><td>{{ l.articulo.nombre }} <span class="muted">· disp. {{ l.articulo.existencia | number: '1.0-3' }}</span></td>
                <td class="num"><input class="celda" type="number" min="0" [ngModel]="l.cantidad" (ngModelChange)="fijar(i, 'cantidad', +$event)" [attr.aria-label]="'Cantidad ' + l.articulo.nombre" /></td>
                <td><app-presentacion [articulo]="l.articulo" [empaque]="!!l.empaque" (cambio)="cambiarUnidad(i, $event)" /></td>
                <td class="num"><input class="celda" type="number" min="0" [ngModel]="l.descuentoPorcentaje" (ngModelChange)="fijar(i, 'descuentoPorcentaje', +$event)" /></td>
                <td><button mat-icon-button (click)="quitar(i)" aria-label="Quitar"><span class="material-symbols-outlined">delete</span></button></td></tr>
            } @empty { <tr><td colspan="5" class="muted">Agregue la mercancía que se entrega</td></tr> }
          </table>
          <mat-form-field style="width:100%;margin-top:12px"><mat-label>Comentario</mat-label><input matInput [(ngModel)]="comentario" maxlength="300" /></mat-form-field>
          <div class="fila">
            <span>Valor estimado (se factura después)</span><span class="espaciador"></span><span class="total">{{ totales().total | currency }}</span>
            <button mat-button (click)="nuevo.set(false)">Cancelar</button>
            <button mat-flat-button [disabled]="!cliente() || !lineas().length" (click)="crear()">Registrar entrega</button>
          </div>
        </div>
      }

      @if (seleccion().length) {
        <div class="tarjeta no-imprimir fila">
          <span>{{ seleccion().length }} conduce(s) seleccionado(s) · {{ totalSeleccion() | currency }}</span>
          <span class="espaciador"></span>
          <mat-form-field style="width:160px"><mat-label>Condición</mat-label><mat-select [(ngModel)]="condicion">
            <mat-option value="CREDITO">Crédito</mat-option><mat-option value="CONTADO">Contado (cobrar ahora en caja)</mat-option></mat-select></mat-form-field>
          <button mat-flat-button (click)="facturar()">Facturar seleccionados</button>
        </div>
      }

      <section class="tarjeta tabla-contenedor no-imprimir">
        <table class="tabla">
          <thead><tr><th></th><th>No.</th><th>Fecha</th><th>Cliente</th><th>Entrega</th><th class="num">Valor</th><th>Estado</th><th></th></tr></thead>
          <tbody>
            @for (c of pagina()?.content ?? []; track c.id) {
              <tr>
                <td>@if (c.estado === 'ABIERTO') { <mat-checkbox [checked]="marcado(c)" (change)="alternar(c)" [aria-label]="'Seleccionar conduce ' + c.numero" /> }</td>
                <td>{{ c.numero }}</td><td>{{ c.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ c.cliente }}</td><td>{{ c.lugarEntrega }}</td>
                <td class="num">{{ c.totalEstimado | currency }}</td>
                <td><span class="chip" [class.ok]="c.estado === 'FACTURADO'" [class.error]="c.estado === 'ANULADO'" [class.info]="c.estado === 'ABIERTO'">{{ c.estado }}</span></td>
                <td style="white-space:nowrap">
                  <button mat-button (click)="ver(c)">Ver</button>
                  @if (c.estado === 'ABIERTO' && auth.esSupervisor()) { <button mat-button (click)="anular(c)">Anular</button> }
                </td>
              </tr>
            } @empty { <tr><td colspan="8" class="muted">Sin conduces</td></tr> }
          </tbody>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
      </section>

      @if (visto(); as c) {
        <section class="tarjeta imprimible">
          <div class="fila"><h2 style="flex:1">Conduce No. {{ c.numero }}</h2>
            <button mat-stroked-button class="no-imprimir" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
            <button mat-button class="no-imprimir" (click)="visto.set(null)">Cerrar</button></div>
          <p>Fecha: {{ c.fecha | date: 'dd/MM/yyyy' }} · Cliente: <strong>{{ c.cliente }}</strong>@if (c.lugarEntrega) { · Entrega: {{ c.lugarEntrega }} }</p>
          <table class="tabla">
            <tr><th>Cant.</th><th>Unidad</th><th>Descripción</th></tr>
            @for (l of c.lineas; track l.linea) { <tr><td>{{ l.cantidad | number: '1.0-3' }}</td><td>{{ l.unidad }}</td><td>{{ l.descripcion }}</td></tr> }
          </table>
          @if (c.comentario) { <p>{{ c.comentario }}</p> }
          <div class="firmas"><div>Entregado por</div><div>Recibido conforme</div></div>
          @if (c.estado === 'FACTURADO') { <p class="no-imprimir muted">Facturado (factura id {{ c.facturaId }}).</p> }
          @if (c.motivoAnulacion) { <p class="no-imprimir error-texto">Anulado: {{ c.motivoAnulacion }}</p> }
        </section>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .celda { width: 90px; padding: 6px; text-align: right; }
    .total { font: var(--mat-sys-headline-small); color: var(--mat-sys-primary); } section { margin-top: 16px; }
    .firmas { display: flex; gap: 48px; margin-top: 64px; div { flex: 1; border-top: 1px solid currentColor; padding-top: 4px; text-align: center; } }
    @media print { .no-imprimir { display: none !important; } }`,
})
export class Conduces {
  protected readonly comp = inject(ComprobanteService);
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly pagina = signal<Pagina<Conduce> | null>(null);
  protected readonly nuevo = signal(false);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly lineas = signal<LineaCarrito[]>([]);
  protected readonly seleccion = signal<Conduce[]>([]);
  protected readonly visto = signal<Conduce | null>(null);
  protected estado: string | null = 'ABIERTO';
  protected condicion: 'CREDITO' | 'CONTADO' = 'CREDITO';
  protected lugarEntrega = '';
  protected comentario = '';
  protected readonly totales = computed(() => calcular(this.lineas(), (this.cliente()?.tipoPrecio as TipoPrecio) ?? 'CONTADO', !!this.cliente()?.exentoItbis));
  protected readonly totalSeleccion = computed(() => this.seleccion().reduce((s, c) => s + (c.totalEstimado ?? 0), 0));

  constructor() { this.cargar(); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Conduce>>('/api/v1/ventas/conduces', { params: params({ estado: this.estado, page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected abrir(): void { this.lineas.set([]); this.cliente.set(null); this.lugarEntrega = ''; this.comentario = ''; this.nuevo.set(true); }
  protected agregar(a: S['ArticuloResponse']): void {
    this.lineas.set([...this.lineas(), { articulo: a, cantidad: 1, descuentoPorcentaje: 0, empaque: ESCANEADOS_EMPAQUE.has(a) }]);
  }
  protected cambiarUnidad(i: number, empaque: boolean): void { this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, empaque } : l))); }
  protected fijar(i: number, campo: 'cantidad' | 'descuentoPorcentaje', v: number): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: Math.max(0, v || 0) } : l)));
  }
  protected quitar(i: number): void { this.lineas.set(this.lineas().filter((_, j) => j !== i)); }

  protected crear(): void {
    const req: S['ConduceRequest'] = { clienteId: this.cliente()!.id!, lugarEntrega: this.lugarEntrega || undefined, comentario: this.comentario || undefined,
      lineas: this.lineas().map((l) => ({ articuloId: l.articulo.id!, cantidad: l.cantidad, descuentoPorcentaje: l.descuentoPorcentaje || undefined,
        empaque: l.empaque || undefined })) };
    this.http.post<Conduce>('/api/v1/ventas/conduces', req).subscribe({
      next: (c) => { this.notif.ok(`Conduce ${c.numero} registrado`); this.nuevo.set(false); this.cargar(); this.visto.set(c); },
      error: (e) => this.notif.error(e) });
  }

  protected marcado(c: Conduce): boolean { return this.seleccion().some((x) => x.id === c.id); }

  protected alternar(c: Conduce): void {
    const s = this.seleccion();
    if (this.marcado(c)) { this.seleccion.set(s.filter((x) => x.id !== c.id)); return; }
    if (s.length && s[0].clienteId !== c.clienteId) { this.notif.error(null, 'Solo se facturan juntos conduces del mismo cliente'); return; }
    this.seleccion.set([...s, c]);
  }

  protected facturar(): void {
    const conduceIds = this.seleccion().map((c) => c.id!);
    const emitida = (f: S['FacturaResponse'], recibo?: S['ReciboResponse']) => {
      this.seleccion.set([]); this.cargar();
      this.notif.ok(`Factura ${f.numero} emitida${this.comp.sufijo(f.encf)}`);
      this.dialog.open(TicketDialog, { data: { factura: f, recibo } });
    };
    if (this.condicion === 'CREDITO') {
      this.http.post<S['FacturaResponse']>('/api/v1/ventas/conduces/facturar', { conduceIds, condicion: 'CREDITO' })
        .subscribe({ next: (f) => emitida(f), error: (e) => this.notif.error(e) });
      return;
    }
    // Contado: se cobra en el momento con el mismo diálogo del punto de venta (entra a la caja como factura a contado)
    const clienteId = this.seleccion()[0]?.clienteId;
    const saldo$ = clienteId ? this.http.get<S['ClienteResponse']>(`/api/v1/catalogos/clientes/${clienteId}`)
      .pipe(map((x) => x.saldoAFavor ?? 0), catchError(() => of(0))) : of(0);
    saldo$.pipe(switchMap((saldoAFavor) => this.dialog.open(PagoDialog, { data: { total: redondear(this.totalSeleccion()), saldoAFavor }, width: 'min(680px, 96vw)', maxWidth: '96vw', autoFocus: 'dialog' })
      .afterClosed())).subscribe((pagos?: S['PagoRequest'][]) => {
        if (!pagos) return;
        this.http.post<S['VentaContadoResponse']>('/api/v1/ventas/conduces/facturar-contado',
          { facturar: { conduceIds, condicion: 'CONTADO' }, pagos })
          .subscribe({ next: (r) => emitida(r.factura!, r.recibo), error: (e) => this.notif.error(e) });
      });
  }

  protected ver(c: Conduce): void { this.visto.set(c); }

  protected anular(c: Conduce): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular conduce ${c.numero}`, accion: 'Anular', peligro: true, pedirMotivo: 'Motivo',
      mensaje: 'La mercancía regresa al almacén.' } }).afterClosed().subscribe((motivo) => motivo &&
      this.http.post(`/api/v1/ventas/conduces/${c.id}/anular`, { motivo }).subscribe({
        next: () => { this.notif.ok('Conduce anulado'); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }

  protected imprimir(): void { window.print(); }
}
