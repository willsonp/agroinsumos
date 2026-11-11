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
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { CuentasBancarias } from '../../shared/cuentas-bancarias';
import { params, redondear } from '../../shared/http';
import { CuentasContables } from '../contabilidad/cuentas-contables';

type Fondo = S['CajaChicaFondoResponse'];
type Movimiento = S['CajaChicaMovimientoResponse'];
type Reposicion = S['CajaChicaReposicion'];
type Arqueo = S['CajaChicaArqueo'];
type Forma = 'EFECTIVO' | 'CHEQUE' | 'TRANSFERENCIA';
interface Usuario { id: number; nombre?: string; username?: string; }

const TIPOS_BIEN: [string, string][] = [
  ['01', '01 · Gastos de personal'], ['02', '02 · Trabajos, suministros y servicios'], ['03', '03 · Arrendamientos'],
  ['04', '04 · Activos fijos'], ['05', '05 · Representación'], ['06', '06 · Otras deducciones admitidas'],
  ['07', '07 · Gastos financieros'], ['08', '08 · Gastos extraordinarios'], ['09', '09 · Costo de venta'],
  ['10', '10 · Adquisiciones de activos'], ['11', '11 · Gastos de seguros'],
];

/** Caja chica (fondo fijo): vales de gasto, reposición por cheque/transferencia/efectivo y arqueos (legacy cajachica). */
@Component({
  selector: 'app-caja-chica',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo no-imprimir"><h1>Caja chica</h1>
        @if (fondos().length > 1) {
          <mat-form-field style="width:240px"><mat-label>Fondo</mat-label>
            <mat-select [ngModel]="actual()?.id" (ngModelChange)="elegir($event)">
              @for (f of fondos(); track f.id) { <mat-option [value]="f.id">{{ f.nombre }}</mat-option> }</mat-select></mat-form-field>
        }
        <span class="espaciador"></span>
        @if (auth.esSupervisor()) {
          @if (actual(); as a) { <button mat-button (click)="editar(a)"><span class="material-symbols-outlined">edit</span> Editar fondo</button> }
          <button mat-flat-button (click)="nuevoFondo()"><span class="material-symbols-outlined">add</span> Nuevo fondo</button>
        }
      </div>

      @if (formFondo(); as ff) {
        <div class="tarjeta no-imprimir">
          <h3>{{ ff.id ? 'Editar fondo' : 'Nuevo fondo de caja chica' }}</h3>
          <div class="grid">
            <mat-form-field><mat-label>Nombre</mat-label><input matInput [(ngModel)]="ff.nombre" maxlength="60" /></mat-form-field>
            <mat-form-field><mat-label>Monto del fondo</mat-label><input matInput type="number" min="1" [(ngModel)]="ff.montoFondo" [disabled]="!!ff.id" /></mat-form-field>
            <mat-form-field><mat-label>Responsable</mat-label><mat-select [(ngModel)]="ff.responsableId">
              <mat-option [value]="null">Sin asignar</mat-option>
              @for (u of usuarios(); track u.id) { <mat-option [value]="u.id">{{ u.nombre || u.username }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Cuenta contable del fondo</mat-label><mat-select [(ngModel)]="ff.cuentaContable">
              <mat-option [value]="null">Por defecto (Caja chica)</mat-option>
              @for (c of cuentasActivo(); track c.id) { <mat-option [value]="c.codigo">{{ etiquetaCuenta(c) }}</mat-option> }</mat-select></mat-form-field>
          </div>
          @if (!ff.id) {
            <p class="muted">Apertura: de dónde sale el dinero del fondo.</p>
            <div class="grid">
              <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="ff.forma">
                @for (f of formas; track f[0]) { <mat-option [value]="f[0]">{{ f[1] }}</mat-option> }</mat-select></mat-form-field>
              @if (ff.forma !== 'EFECTIVO') {
                <mat-form-field><mat-label>Cuenta bancaria</mat-label><mat-select [(ngModel)]="ff.cuentaBancariaId">
                  @for (c of bancos.enPesos(); track c.id) { <mat-option [value]="c.id">{{ etiquetaBanco(c) }}</mat-option> }</mat-select></mat-form-field>
                <mat-form-field><mat-label>{{ ff.forma === 'CHEQUE' ? 'No. cheque (vacío = siguiente)' : 'Referencia' }}</mat-label>
                  <input matInput [(ngModel)]="ff.referencia" maxlength="40" /></mat-form-field>
              } @else { <p class="muted">El efectivo sale de su caja abierta.</p> }
            </div>
          }
          <div class="fila"><span class="espaciador"></span>
            <button mat-button (click)="formFondo.set(null)">Cancelar</button>
            <button mat-flat-button [disabled]="!ff.nombre.trim() || ff.montoFondo <= 0 || (!ff.id && ff.forma !== 'EFECTIVO' && !ff.cuentaBancariaId)"
              (click)="guardarFondo(ff)">{{ ff.id ? 'Guardar' : 'Crear y abrir fondo' }}</button></div>
        </div>
      }

      @if (actual(); as a) {
        <div class="resumen no-imprimir">
          <div class="tarjeta kpi"><span>Fondo</span><strong>{{ a.montoFondo | currency }}</strong></div>
          <div class="tarjeta kpi"><span>Disponible</span><strong [class.error-texto]="(a.saldo ?? 0) < (a.montoFondo ?? 0) * 0.2">{{ a.saldo | currency }}</strong></div>
          <div class="tarjeta kpi"><span>Por reponer ({{ a.gastosPendientes }} vales)</span><strong>{{ a.porReponer | currency }}</strong></div>
          <div class="tarjeta kpi"><span>Responsable</span><strong class="texto">{{ a.responsable || '—' }}</strong></div>
        </div>

        <mat-tab-group class="no-imprimir" [(selectedIndex)]="tab" (selectedIndexChange)="alCambiarTab()">
          <mat-tab label="Vales y movimientos">
            <div class="tarjeta">
              <h3>Registrar vale de gasto</h3>
              <div class="grid">
                <mat-form-field><mat-label>Fecha</mat-label><input matInput type="date" [(ngModel)]="g.fecha" /></mat-form-field>
                <mat-form-field class="ancho"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="g.concepto" maxlength="200" /></mat-form-field>
                <mat-form-field><mat-label>Beneficiario</mat-label><input matInput [(ngModel)]="g.beneficiario" maxlength="100" /></mat-form-field>
                <mat-form-field><mat-label>Monto total</mat-label><input matInput type="number" min="0" [(ngModel)]="g.monto" /></mat-form-field>
                <mat-form-field><mat-label>Cuenta de gasto</mat-label><mat-select [(ngModel)]="g.cuentaGasto">
                  <mat-option [value]="null">Por defecto (Gastos de caja chica)</mat-option>
                  @for (c of cuentasGasto(); track c.id) { <mat-option [value]="c.codigo">{{ etiquetaCuenta(c) }}</mat-option> }</mat-select></mat-form-field>
              </div>
              <p class="muted">Si el suplidor entregó comprobante fiscal, complete RNC y NCF: el gasto va al formato 606 y su ITBIS se adelanta.</p>
              <div class="grid">
                <mat-form-field><mat-label>RNC / Cédula</mat-label><input matInput [(ngModel)]="g.rnc" maxlength="11" /></mat-form-field>
                <mat-form-field><mat-label>NCF</mat-label><input matInput [(ngModel)]="g.ncf" maxlength="19" (ngModelChange)="g.ncf = $event.toUpperCase()" /></mat-form-field>
                <mat-form-field><mat-label>ITBIS incluido</mat-label><input matInput type="number" min="0" [(ngModel)]="g.itbis" [disabled]="!g.ncf" /></mat-form-field>
                <mat-form-field><mat-label>Tipo bienes y servicios (606)</mat-label><mat-select [(ngModel)]="g.tipoBienServicio" [disabled]="!g.ncf">
                  @for (t of tiposBien; track t[0]) { <mat-option [value]="t[0]">{{ t[1] }}</mat-option> }</mat-select></mat-form-field>
              </div>
              <div class="fila"><span class="espaciador"></span>
                <button mat-flat-button [disabled]="!g.concepto.trim() || g.monto <= 0 || g.monto > (a.saldo ?? 0) || (!!g.ncf && !g.rnc)" (click)="registrarGasto(a)">
                  <span class="material-symbols-outlined">receipt</span> Registrar vale</button></div>
            </div>

            <section class="tarjeta tabla-contenedor">
              <div class="fila">
                <mat-form-field><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="desde" (change)="cargarMovimientos()" /></mat-form-field>
                <mat-form-field><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="hasta" (change)="cargarMovimientos()" /></mat-form-field>
              </div>
              <table class="tabla">
                <thead><tr><th>No.</th><th>Fecha</th><th>Tipo</th><th>Concepto</th><th>Comprobante</th><th class="num">Monto</th><th>Estado</th><th></th></tr></thead>
                <tbody>
                  @for (m of movimientos(); track m.id) {
                    <tr [class.anulado]="m.estado === 'ANULADO'">
                      <td>{{ m.numero }}</td><td>{{ m.fecha | date: 'dd/MM/yyyy' }}</td>
                      <td><span class="chip" [class.info]="m.tipo !== 'GASTO'">{{ tipoTexto(m) }}</span></td>
                      <td>{{ m.concepto }}@if (m.beneficiario) { <div class="muted">{{ m.beneficiario }}</div> }
                        @if (m.motivoAnulacion) { <div class="error-texto">Anulado: {{ m.motivoAnulacion }}</div> }</td>
                      <td>@if (m.ncf) { {{ m.ncf }} <div class="muted">RNC {{ m.rnc }}</div> }</td>
                      <td class="num">{{ (m.tipo === 'GASTO' ? -(m.monto ?? 0) : m.monto) | currency }}</td>
                      <td>@if (m.tipo === 'GASTO' && m.estado === 'REGISTRADO') {
                            <span class="chip" [class.ok]="m.reposicionId" [class.alerta]="!m.reposicionId">{{ m.reposicionId ? 'Repuesto' : 'Por reponer' }}</span>
                          } @else { <span class="chip" [class.error]="m.estado === 'ANULADO'">{{ m.estado }}</span> }</td>
                      <td style="white-space:nowrap">
                        @if (m.tipo === 'REPOSICION') { <button mat-button (click)="verReposicion(m.id!)">Ver detalle</button> }
                        @if (m.tipo === 'GASTO' && m.estado === 'REGISTRADO' && !m.reposicionId && auth.esSupervisor()) {
                          <button mat-button (click)="anular(m)">Anular</button> }
                      </td>
                    </tr>
                  } @empty { <tr><td colspan="8" class="muted">Sin movimientos en el período</td></tr> }
                </tbody>
              </table>
            </section>
          </mat-tab>

          @if (auth.esSupervisor()) {
            <mat-tab label="Reposición">
              <div class="tarjeta">
                @if ((a.gastosPendientes ?? 0) > 0) {
                  <p>Se repondrán <strong>{{ a.porReponer | currency }}</strong> correspondientes a {{ a.gastosPendientes }} vale(s); el fondo queda en {{ a.montoFondo | currency }}.</p>
                  <div class="grid">
                    <mat-form-field><mat-label>Forma</mat-label><mat-select [(ngModel)]="rep.forma">
                      @for (f of formas; track f[0]) { <mat-option [value]="f[0]">{{ f[1] }}</mat-option> }</mat-select></mat-form-field>
                    @if (rep.forma !== 'EFECTIVO') {
                      <mat-form-field><mat-label>Cuenta bancaria</mat-label><mat-select [(ngModel)]="rep.cuentaBancariaId">
                        @for (c of bancos.enPesos(); track c.id) { <mat-option [value]="c.id">{{ etiquetaBanco(c) }}</mat-option> }</mat-select></mat-form-field>
                      <mat-form-field><mat-label>{{ rep.forma === 'CHEQUE' ? 'No. cheque (vacío = siguiente)' : 'Referencia' }}</mat-label>
                        <input matInput [(ngModel)]="rep.referencia" maxlength="40" /></mat-form-field>
                    } @else { <p class="muted">El efectivo sale de su caja abierta.</p> }
                  </div>
                  <div class="fila"><span class="espaciador"></span>
                    <button mat-flat-button [disabled]="rep.forma !== 'EFECTIVO' && !rep.cuentaBancariaId" (click)="reponer(a)">
                      <span class="material-symbols-outlined">autorenew</span> Reponer {{ a.porReponer | currency }}</button></div>
                } @else { <p class="muted">No hay vales pendientes de reponer.</p> }
              </div>
            </mat-tab>
          }

          <mat-tab label="Arqueos">
            <div class="tarjeta">
              <div class="fila">
                <mat-form-field><mat-label>Efectivo contado</mat-label><input matInput type="number" min="0" [(ngModel)]="arq.efectivo" /></mat-form-field>
                <mat-form-field style="flex:1 1 280px"><mat-label>Observación</mat-label><input matInput [(ngModel)]="arq.observacion" maxlength="200" /></mat-form-field>
                <span class="muted">Saldo del sistema: {{ a.saldo | currency }} · Diferencia: {{ arq.efectivo - (a.saldo ?? 0) | currency }}</span>
                <button mat-flat-button [disabled]="arq.efectivo < 0" (click)="registrarArqueo(a)">Registrar arqueo</button>
              </div>
              <table class="tabla">
                <thead><tr><th>Fecha</th><th class="num">Contado</th><th class="num">Sistema</th><th class="num">Diferencia</th><th>Observación</th></tr></thead>
                <tbody>
                  @for (x of arqueos(); track x.id) {
                    <tr><td>{{ x.fecha | date: 'dd/MM/yyyy hh:mm a' }}</td><td class="num">{{ x.efectivoContado | currency }}</td>
                      <td class="num">{{ x.saldoSistema | currency }}</td>
                      <td class="num" [class.error-texto]="x.diferencia !== 0">{{ x.diferencia | currency }}</td><td>{{ x.observacion }}</td></tr>
                  } @empty { <tr><td colspan="5" class="muted">Sin arqueos</td></tr> }
                </tbody>
              </table>
            </div>
          </mat-tab>
        </mat-tab-group>
      } @else if (!formFondo()) {
        <div class="tarjeta"><p>No hay fondos de caja chica. @if (auth.esSupervisor()) { Cree uno con "Nuevo fondo". }</p></div>
      }

      @if (reposicion(); as r) {
        <div class="tarjeta hoja">
          <header class="encabezado">
            <div><strong>{{ empresa()?.nombreComercial || empresa()?.nombre }}</strong><div class="muted">RNC {{ empresa()?.rnc }}</div></div>
            <div class="titulo"><h2>Reposición de caja chica No. {{ r.reposicion?.numero }}</h2>
              <div>{{ r.fondo?.nombre }} · {{ r.reposicion?.fecha | date: 'dd/MM/yyyy' }} · {{ r.reposicion?.concepto }}</div></div>
            <div class="no-imprimir"><button mat-stroked-button (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
              <button mat-button (click)="reposicion.set(null)">Cerrar</button></div>
          </header>
          <table class="tabla">
            <thead><tr><th>Vale</th><th>Fecha</th><th>Concepto</th><th>Beneficiario</th><th>NCF</th><th class="num">ITBIS</th><th class="num">Monto</th></tr></thead>
            <tbody>
              @for (m of r.gastos; track m.id) {
                <tr><td>{{ m.numero }}</td><td>{{ m.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ m.concepto }}</td><td>{{ m.beneficiario }}</td>
                  <td>{{ m.ncf }}</td><td class="num">{{ m.itbis | currency }}</td><td class="num">{{ m.monto | currency }}</td></tr>
              }
            </tbody>
            <tfoot><tr><td colspan="5">TOTAL ({{ r.gastos?.length }} vales)</td><td class="num">{{ r.itbis | currency }}</td><td class="num">{{ r.reposicion?.monto | currency }}</td></tr></tfoot>
          </table>
          <div class="firmas"><div>Preparado por</div><div>Revisado por</div><div>Aprobado por</div></div>
        </div>
      }
    </div>`,
  styles: `.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 18px 12px; align-items: start; } .grid .ancho { grid-column: span 2; }
    @media (max-width: 600px) { .grid .ancho { grid-column: auto; } }
    .resumen { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 12px; }
    .kpi { display: flex; flex-direction: column; gap: 4px; span { color: var(--mat-sys-on-surface-variant); font-size: .85em; }
      strong { font: var(--mat-sys-headline-small); } strong.texto { font: var(--mat-sys-title-medium); } }
    .tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; white-space: nowrap; } tfoot td { font-weight: 600; border-top: 2px solid var(--mat-sys-outline); }
    .anulado td { opacity: .55; } .tarjeta { margin-top: 12px; } h3 { margin-top: 0; }
    .encabezado { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; margin-bottom: 12px; .titulo { flex: 1; text-align: center; h2 { margin: 0; } } }
    .firmas { display: grid; grid-template-columns: repeat(3, 1fr); gap: 32px; margin-top: 64px; text-align: center;
      div { border-top: 1px solid var(--mat-sys-outline); padding-top: 4px; } }
    @media print { .hoja { box-shadow: none !important; padding: 0 !important; margin: 0 !important; } }`,
})
export class CajaChica {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly contables = inject(CuentasContables);
  protected readonly bancos = inject(CuentasBancarias);
  protected readonly auth = inject(AuthService);
  protected readonly formas: [Forma, string][] = [['CHEQUE', 'Cheque'], ['TRANSFERENCIA', 'Transferencia'], ['EFECTIVO', 'Efectivo de caja']];
  protected readonly tiposBien = TIPOS_BIEN;
  protected readonly fondos = signal<Fondo[]>([]);
  protected readonly actual = signal<Fondo | null>(null);
  protected readonly movimientos = signal<Movimiento[]>([]);
  protected readonly arqueos = signal<Arqueo[]>([]);
  protected readonly reposicion = signal<Reposicion | null>(null);
  protected readonly usuarios = signal<Usuario[]>([]);
  protected readonly empresa = signal<S['CompaniaResponse'] | null>(null);
  protected readonly formFondo = signal<{ id?: number; nombre: string; montoFondo: number; responsableId: number | null; cuentaContable: string | null;
    forma: Forma; cuentaBancariaId: number | null; referencia: string } | null>(null);
  protected readonly cuentasGasto = computed(() => this.contables.detalle().filter((c) => c.tipo === 'GASTO' || c.tipo === 'COSTO'));
  protected readonly cuentasActivo = computed(() => this.contables.detalle().filter((c) => c.tipo === 'ACTIVO'));
  protected tab = 0;
  protected desde = this.primeroDeMes();
  protected hasta = this.hoy();
  protected g = this.gastoVacio();
  protected rep = { forma: 'CHEQUE' as Forma, cuentaBancariaId: null as number | null, referencia: '' };
  protected arq = { efectivo: 0, observacion: '' };

  constructor() {
    this.cargar();
    this.contables.cargar();
    this.bancos.cargar();
    this.http.get<S['CompaniaResponse']>('/api/v1/admin/empresa').subscribe({ next: (e) => this.empresa.set(e), error: () => {} });
    if (this.auth.esSupervisor()) {
      this.http.get<Pagina<Usuario>>('/api/v1/usuarios', { params: params({ size: 200 }) })
        .subscribe({ next: (p) => this.usuarios.set(p.content), error: () => this.usuarios.set([]) });
    }
  }

  private hoy(): string { const d = new Date(); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); }
  private primeroDeMes(): string { return this.hoy().slice(0, 8) + '01'; }
  private gastoVacio() {
    return { fecha: this.hoy(), concepto: '', beneficiario: '', monto: 0, cuentaGasto: null as string | null, rnc: '', ncf: '', itbis: 0, tipoBienServicio: '02' };
  }

  protected etiquetaCuenta = CuentasContables.etiqueta;
  protected etiquetaBanco = CuentasBancarias.etiqueta;

  protected cargar(seleccion?: number): void {
    this.http.get<Fondo[]>('/api/v1/caja/chica').subscribe({
      next: (f) => {
        this.fondos.set(f);
        const id = seleccion ?? this.actual()?.id;
        this.actual.set(f.find((x) => x.id === id) ?? f[0] ?? null);
        this.cargarMovimientos(); this.cargarArqueos();
      },
      error: (e) => this.notif.error(e) });
  }

  protected elegir(id: number): void { this.actual.set(this.fondos().find((f) => f.id === id) ?? null); this.cargarMovimientos(); this.cargarArqueos(); }

  protected alCambiarTab(): void { if (this.tab === 0) this.cargarMovimientos(); }

  protected cargarMovimientos(): void {
    const a = this.actual();
    if (!a) { this.movimientos.set([]); return; }
    this.http.get<Movimiento[]>(`/api/v1/caja/chica/${a.id}/movimientos`, { params: params({ desde: this.desde || null, hasta: this.hasta || null }) })
      .subscribe({ next: (m) => this.movimientos.set(m), error: (e) => this.notif.error(e) });
  }

  private cargarArqueos(): void {
    const a = this.actual();
    if (!a) { this.arqueos.set([]); return; }
    this.http.get<Arqueo[]>(`/api/v1/caja/chica/${a.id}/arqueos`).subscribe({ next: (x) => this.arqueos.set(x), error: () => this.arqueos.set([]) });
  }

  protected tipoTexto(m: Movimiento): string { return m.tipo === 'GASTO' ? 'Vale' : m.tipo === 'APERTURA' ? 'Apertura' : 'Reposición'; }

  protected nuevoFondo(): void {
    this.formFondo.set({ nombre: 'Caja chica', montoFondo: 5000, responsableId: null, cuentaContable: null, forma: 'CHEQUE', cuentaBancariaId: null, referencia: '' });
  }

  protected editar(a: Fondo): void {
    this.formFondo.set({ id: a.id, nombre: a.nombre ?? '', montoFondo: a.montoFondo ?? 0, responsableId: a.responsableId ?? null,
      cuentaContable: a.cuentaContable ?? null, forma: 'CHEQUE', cuentaBancariaId: null, referencia: '' });
  }

  protected guardarFondo(ff: NonNullable<ReturnType<typeof this.formFondo>>): void {
    const req: S['CajaChicaFondoRequest'] = { nombre: ff.nombre.trim(), montoFondo: redondear(ff.montoFondo), responsableId: ff.responsableId ?? undefined,
      cuentaContable: ff.cuentaContable ?? undefined,
      apertura: ff.id ? undefined : { forma: ff.forma, cuentaBancariaId: ff.forma === 'EFECTIVO' ? undefined : ff.cuentaBancariaId ?? undefined,
        referencia: ff.referencia.trim() || undefined } };
    const op = ff.id ? this.http.put<Fondo>(`/api/v1/caja/chica/${ff.id}`, req) : this.http.post<Fondo>('/api/v1/caja/chica', req);
    op.subscribe({ next: (f) => { this.notif.ok(ff.id ? 'Fondo actualizado' : `Fondo "${f.nombre}" abierto con RD$${f.saldo}`); this.formFondo.set(null); this.cargar(f.id); },
      error: (e) => this.notif.error(e) });
  }

  protected registrarGasto(a: Fondo): void {
    const conNcf = !!this.g.ncf.trim();
    const req: S['CajaChicaGastoRequest'] = { fecha: this.g.fecha || undefined, concepto: this.g.concepto.trim(), beneficiario: this.g.beneficiario.trim() || undefined,
      monto: redondear(this.g.monto), cuentaGasto: this.g.cuentaGasto ?? undefined,
      rnc: this.g.rnc.trim() || undefined, ncf: conNcf ? this.g.ncf.trim() : undefined,
      itbis: conNcf ? redondear(this.g.itbis || 0) : undefined, tipoBienServicio: conNcf ? this.g.tipoBienServicio : undefined };
    this.http.post<Movimiento>(`/api/v1/caja/chica/${a.id}/gastos`, req).subscribe({
      next: (m) => { this.notif.ok(`Vale ${m.numero} registrado`); this.g = this.gastoVacio(); this.cargar(); },
      error: (e) => this.notif.error(e) });
  }

  protected anular(m: Movimiento): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular vale ${m.numero}`, accion: 'Anular', peligro: true, pedirMotivo: 'Motivo',
      mensaje: `Se devuelven RD$${m.monto} al saldo de la caja chica.` } }).afterClosed().subscribe((motivo) => {
      if (!motivo) return;
      this.http.post(`/api/v1/caja/chica/movimientos/${m.id}/anular`, { motivo }).subscribe({
        next: () => { this.notif.ok('Vale anulado'); this.cargar(); }, error: (e) => this.notif.error(e) });
    });
  }

  protected reponer(a: Fondo): void {
    const req: S['CajaChicaOrigen'] = { forma: this.rep.forma, cuentaBancariaId: this.rep.forma === 'EFECTIVO' ? undefined : this.rep.cuentaBancariaId ?? undefined,
      referencia: this.rep.referencia.trim() || undefined };
    this.http.post<Reposicion>(`/api/v1/caja/chica/${a.id}/reposiciones`, req).subscribe({
      next: (r) => { this.notif.ok(`Reposición ${r.reposicion?.numero} registrada por RD$${r.reposicion?.monto}`); this.rep.referencia = '';
        this.reposicion.set(r); this.tab = 0; this.cargar(); },
      error: (e) => this.notif.error(e) });
  }

  protected verReposicion(id: number): void {
    this.http.get<Reposicion>(`/api/v1/caja/chica/reposiciones/${id}`).subscribe({ next: (r) => this.reposicion.set(r), error: (e) => this.notif.error(e) });
  }

  protected registrarArqueo(a: Fondo): void {
    this.http.post<Arqueo>(`/api/v1/caja/chica/${a.id}/arqueos`, { efectivoContado: redondear(this.arq.efectivo), observacion: this.arq.observacion.trim() || undefined })
      .subscribe({ next: (x) => { this.notif.ok(x.diferencia === 0 ? 'Arqueo cuadrado' : `Arqueo con diferencia de RD$${x.diferencia}`);
        this.arq = { efectivo: 0, observacion: '' }; this.cargarArqueos(); }, error: (e) => this.notif.error(e) });
  }

  protected imprimir(): void { window.print(); }
}
