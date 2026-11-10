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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ClienteSelector } from '../../shared/cliente-selector';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { params, redondear } from '../../shared/http';

type Plan = S['FinanciamientoResponse'];
type Pendiente = S['EstadoCuentaDocumentoPendiente'];

/** Ventas financiadas en cuotas con interés y mora (legacy cuotas / facturapro financiada). */
@Component({
  selector: 'app-financiamientos',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule,
    MatPaginatorModule, ClienteSelector],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Ventas financiadas</h1></div>
      <mat-tab-group [selectedIndex]="pestana()" (selectedIndexChange)="cambiarPestana($event)">
        <mat-tab label="Planes">
          <div class="tarjeta">
            <table class="tabla">
              <thead><tr><th>No.</th><th>Fecha</th><th>Cliente</th><th>Factura</th><th class="num">Financiado</th><th class="num">Cobrado</th>
                <th class="num">Pendiente</th><th>Cuotas vencidas</th><th></th></tr></thead>
              <tbody>
                @for (p of planes()?.content ?? []; track p.id) {
                  <tr class="clickable" (click)="visto.set(visto()?.id === p.id ? null : p)">
                    <td>{{ p.numero }}</td><td>{{ p.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ p.cliente }}</td><td>{{ p.facturaNumero }} · {{ p.encf }}</td>
                    <td class="num">{{ p.totalPlan | currency }}</td><td class="num">{{ p.cobrado | currency }}</td><td class="num">{{ p.pendiente | currency }}</td>
                    <td>@if (p.cuotasVencidas) { <span class="chip error">{{ p.cuotasVencidas }} · {{ p.montoVencido | currency }}</span> }
                      @if (p.estado === 'CANCELADO') { <span class="chip">Cancelado</span> }</td>
                    <td (click)="$event.stopPropagation()">@if (puedeGestionar() && (p.moraPorCobrar ?? 0) > 0) {
                      <button mat-button (click)="mora(p)">Cobrar mora {{ p.moraPorCobrar | currency }}</button> }</td>
                  </tr>
                  @if (visto()?.id === p.id) {
                    <tr class="detalle"><td colspan="9">
                      <p>Capital {{ p.capital | currency }} + interés {{ p.tasaInteres }}% = {{ p.interes | currency }} · {{ p.cantidadCuotas }} cuotas
                        {{ frecuencias[p.frecuenciaDias!] }} · mora {{ p.moraPorcentaje }}% tras {{ p.diasGracia }} día(s) de gracia.
                        Los cobros se registran en Cobros, a la factura y a la nota de débito de intereses.</p>
                      <table class="tabla interna">
                        <tr><th>Cuota</th><th>Vence</th><th class="num">Monto</th><th class="num">Pagado</th><th class="num">Pendiente</th><th>Atraso</th><th class="num">Mora</th></tr>
                        @for (c of p.cuotas; track c.numero) {
                          <tr [class.vencida]="c.vencida"><td>{{ c.numero }}</td><td>{{ c.vencimiento | date: 'dd/MM/yyyy' }}</td><td class="num">{{ c.monto | currency }}</td>
                            <td class="num">{{ c.pagado | currency }}</td><td class="num">{{ c.pendiente | currency }}</td>
                            <td>{{ c.vencida ? c.diasAtraso + ' día(s)' : '' }}</td>
                            <td class="num">{{ c.moraCobrada ? 'cobrada' : (c.moraPorCobrar ? (c.moraPorCobrar | currency) : '') }}</td></tr>
                        }
                      </table>
                      @if (puedeGestionar() && p.estado === 'ACTIVO') { <button mat-button (click)="cancelar(p)">Cancelar plan</button> }
                    </td></tr>
                  }
                } @empty { <tr><td colspan="9" class="muted">Sin ventas financiadas</td></tr> }
              </tbody>
            </table>
            <mat-paginator [length]="planes()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
          </div>
        </mat-tab>

        <mat-tab label="Cuotas vencidas">
          <div class="tarjeta">
            <div class="fila">
              <mat-form-field><mat-label>Al</mat-label><input matInput type="date" [(ngModel)]="corte" (change)="cargarVencidas()" /></mat-form-field>
              <mat-form-field><mat-label>Zona</mat-label><mat-select [(ngModel)]="zonaId" (selectionChange)="cargarVencidas()">
                <mat-option [value]="null">Todas</mat-option>
                @for (z of zonas(); track z.id) { <mat-option [value]="z.id">{{ z.nombre }}</mat-option> }</mat-select></mat-form-field>
              <span class="espaciador"></span><strong>Total vencido {{ totalVencido() | currency }}</strong>
            </div>
            <table class="tabla">
              <tr><th>Cliente</th><th>Teléfono</th><th>Plan</th><th>Factura</th><th>Cuota</th><th>Venció</th><th>Atraso</th><th class="num">Pendiente</th><th class="num">Mora</th></tr>
              @for (v of vencidas(); track $index) {
                <tr><td>{{ v.cliente }}</td><td>{{ v.telefono }}</td><td>{{ v.planNumero }}</td><td>{{ v.facturaNumero }}</td><td>{{ v.cuota }}</td>
                  <td>{{ v.vencimiento | date: 'dd/MM/yyyy' }}</td><td>{{ v.diasAtraso }} día(s)</td><td class="num">{{ v.pendiente | currency }}</td>
                  <td class="num">{{ v.moraPorCobrar | currency }}</td></tr>
              } @empty { <tr><td colspan="9" class="muted">Sin cuotas vencidas</td></tr> }
            </table>
          </div>
        </mat-tab>

        @if (puedeGestionar()) {
          <mat-tab label="Financiar una factura">
            <div class="tarjeta">
              <p class="muted">Registre la factura a crédito y, si hay inicial, cóbrela primero en Cobros. El saldo pendiente se reparte en cuotas;
                el interés se emite como nota de débito (e-CF 33, exenta de ITBIS).</p>
              <div class="fila"><div style="flex:1 1 320px"><app-cliente-selector [cliente]="cliente()" (clienteChange)="elegirCliente($event)" /></div></div>
              @if (cliente()) {
                <mat-form-field style="width:100%"><mat-label>Factura a financiar</mat-label><mat-select [(ngModel)]="facturaId">
                  @for (d of facturas(); track d.id) {
                    <mat-option [value]="d.id">Factura {{ d.numero }} · {{ d.comprobante }} · {{ d.fecha | date: 'dd/MM/yyyy' }} · saldo {{ d.balance | currency }}</mat-option>
                  } @empty { <mat-option disabled>El cliente no tiene facturas pendientes</mat-option> }
                </mat-select></mat-form-field>
                <div class="grid-form">
                  <mat-form-field><mat-label>Interés total (%)</mat-label><input matInput type="number" min="0" [(ngModel)]="f.tasaInteres" /></mat-form-field>
                  <mat-form-field><mat-label>Cantidad de cuotas</mat-label><input matInput type="number" min="1" max="120" [(ngModel)]="f.cantidadCuotas" /></mat-form-field>
                  <mat-form-field><mat-label>Frecuencia</mat-label><mat-select [(ngModel)]="f.frecuenciaDias">
                    <mat-option [value]="30">Mensual</mat-option><mat-option [value]="15">Quincenal</mat-option><mat-option [value]="7">Semanal</mat-option></mat-select></mat-form-field>
                  <mat-form-field><mat-label>Primera cuota</mat-label><input matInput type="date" [(ngModel)]="f.primeraCuota" /></mat-form-field>
                  <mat-form-field><mat-label>Mora por cuota vencida (%)</mat-label><input matInput type="number" min="0" [(ngModel)]="f.moraPorcentaje" /></mat-form-field>
                  <mat-form-field><mat-label>Días de gracia</mat-label><input matInput type="number" min="0" [(ngModel)]="f.diasGracia" /></mat-form-field>
                </div>
                @if (previa(); as v) {
                  <p>Saldo {{ v.capital | currency }} + interés {{ v.interes | currency }} = <strong>{{ v.total | currency }}</strong>
                    en {{ f.cantidadCuotas }} cuotas de <strong>{{ v.cuota | currency }}</strong> (la última ajusta el redondeo).</p>
                }
                <button mat-flat-button [disabled]="!facturaId || f.cantidadCuotas < 1" (click)="crear()">Crear plan</button>
              }
            </div>
          </mat-tab>
        }
      </mat-tab-group>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .clickable { cursor: pointer; } .detalle > td { background: var(--mat-sys-surface-container-low); }
    .interna { max-width: 860px; } tr.vencida td { color: var(--mat-sys-error); } .chip { margin-right: 4px; }`,
})
export class Financiamientos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly frecuencias: Record<number, string> = { 30: 'mensuales', 15: 'quincenales', 7: 'semanales' };
  protected readonly puedeGestionar = computed(() => this.auth.esSupervisor() && this.auth.puede('CXC_FINANCIAMIENTO'));
  protected readonly pestana = signal(0);
  protected readonly planes = signal<Pagina<Plan> | null>(null);
  protected readonly visto = signal<Plan | null>(null);
  protected readonly vencidas = signal<S['FinanciamientoCuotaVencida'][]>([]);
  protected readonly zonas = signal<{ id: number; nombre: string }[]>([]);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly facturas = signal<Pendiente[]>([]);
  protected readonly totalVencido = computed(() => redondear(this.vencidas().reduce((s, v) => s + (v.pendiente ?? 0), 0)));
  protected corte = new Date().toISOString().slice(0, 10);
  protected zonaId: number | null = null;
  protected facturaId: number | null = null;
  protected f = { tasaInteres: 0, cantidadCuotas: 6, frecuenciaDias: 30, primeraCuota: '', moraPorcentaje: 0, diasGracia: 5 };

  constructor() {
    this.cargar();
    this.http.get<Pagina<{ id: number; nombre: string }>>('/api/v1/catalogos/zonas', { params: params({ size: 200 }) })
      .subscribe({ next: (p) => this.zonas.set(p.content), error: () => this.zonas.set([]) });
  }

  /** Vista previa del plan (el servidor hace el cálculo definitivo). */
  protected previa() {
    const d = this.facturas().find((x) => x.id === this.facturaId);
    if (!d || this.f.cantidadCuotas < 1) return null;
    const capital = d.balance ?? 0, interes = redondear(capital * (this.f.tasaInteres || 0) / 100), total = redondear(capital + interes);
    return { capital, interes, total, cuota: redondear(total / this.f.cantidadCuotas) };
  }

  protected cambiarPestana(i: number): void {
    this.pestana.set(i);
    if (i === 1) this.cargarVencidas();
  }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<Plan>>('/api/v1/cxc/financiamientos', { params: params({ page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.planes.set(p), error: (err) => this.notif.error(err) });
  }

  protected cargarVencidas(): void {
    this.http.get<S['FinanciamientoCuotaVencida'][]>('/api/v1/cxc/financiamientos/vencidas', { params: params({ fecha: this.corte, zonaId: this.zonaId }) })
      .subscribe({ next: (v) => this.vencidas.set(v), error: (err) => this.notif.error(err) });
  }

  protected elegirCliente(c: S['ClienteResponse'] | null): void {
    this.cliente.set(c);
    this.facturaId = null;
    this.facturas.set([]);
    if (c) this.http.get<Pendiente[]>(`/api/v1/cxc/clientes/${c.id}/pendientes`)
      .subscribe({ next: (d) => this.facturas.set(d.filter((x) => x.tipo === 'FACTURA')), error: (err) => this.notif.error(err) });
  }

  protected crear(): void {
    const req: S['FinanciamientoRequest'] = { facturaId: this.facturaId!, tasaInteres: this.f.tasaInteres || 0, cantidadCuotas: this.f.cantidadCuotas,
      frecuenciaDias: this.f.frecuenciaDias, primeraCuota: this.f.primeraCuota || undefined, moraPorcentaje: this.f.moraPorcentaje || 0,
      diasGracia: this.f.diasGracia || 0 };
    this.http.post<Plan>('/api/v1/cxc/financiamientos', req).subscribe({
      next: (p) => {
        this.notif.ok(`Plan ${p.numero} creado: ${p.cantidadCuotas} cuotas` + (p.notaDebitoId ? ' · nota de débito de intereses emitida' : ''));
        this.cargar(); this.elegirCliente(null); this.pestana.set(0); this.visto.set(p);
      },
      error: (e) => this.notif.error(e) });
  }

  protected mora(p: Plan): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Cobrar mora del plan ${p.numero}`, accion: 'Emitir nota de débito',
      mensaje: `Se emitirá una nota de débito (e-CF 33) por RD$${p.moraPorCobrar} de mora de las cuotas vencidas.` } })
      .afterClosed().subscribe((ok) => ok && this.http.post<Plan>(`/api/v1/cxc/financiamientos/${p.id}/mora`, {}).subscribe({
        next: (r) => { this.notif.ok('Nota de débito por mora emitida'); this.cargar(); this.visto.set(r); }, error: (e) => this.notif.error(e) }));
  }

  protected cancelar(p: Plan): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Cancelar plan ${p.numero}`, accion: 'Cancelar plan', peligro: true, pedirMotivo: 'Motivo',
      mensaje: 'La factura y la nota de intereses siguen pendientes en la cuenta del cliente; solo se deja de controlar por cuotas.' } })
      .afterClosed().subscribe((motivo) => motivo && this.http.post(`/api/v1/cxc/financiamientos/${p.id}/cancelar`, { motivo }).subscribe({
        next: () => { this.notif.ok('Plan cancelado'); this.cargar(); this.visto.set(null); }, error: (e) => this.notif.error(e) }));
  }
}
