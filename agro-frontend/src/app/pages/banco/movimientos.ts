import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import type { Pagina, S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { CuentasBancarias } from '../../shared/cuentas-bancarias';
import { params } from '../../shared/http';

type Mov = S['MovimientoBancarioResponse'];
type Tipo = Mov['tipo'];
type Formulario = 'DEPOSITO' | 'CHEQUE' | 'NOTA_DEBITO' | 'NOTA_CREDITO' | 'TRANSFERENCIA';

export const TIPOS_MOVIMIENTO: Record<string, string> = {
  DEPOSITO: 'Depósito', CHEQUE: 'Cheque', TRANSFERENCIA_ENTRADA: 'Transferencia recibida',
  TRANSFERENCIA_SALIDA: 'Transferencia enviada', NOTA_DEBITO: 'Nota de débito', NOTA_CREDITO: 'Nota de crédito',
};

const ORIGENES: Record<string, string> = {
  MANUAL: 'Manual', DEPOSITO_CAJA: 'Depósito de caja', TRANSFERENCIA: 'Entre cuentas',
  PAGO_PROVEEDOR: 'Pago a suplidor', RECIBO: 'Recibo de cobro',
};

/** Movimientos bancarios: depósitos, cheques, transferencias y notas de débito/crédito (legacy transaccionesbancarias). */
@Component({
  selector: 'app-movimientos-bancarios',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatTableModule, MatPaginatorModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Movimientos bancarios</h1>
        <span class="espaciador"></span>
        @if (puedeRegistrar() && !formulario()) {
          <button mat-flat-button (click)="abrir('DEPOSITO')"><span class="material-symbols-outlined">add</span> Nuevo movimiento</button>
        }
      </div>

      @if (formulario(); as f) {
        <div class="tarjeta">
          <mat-button-toggle-group [ngModel]="f" (ngModelChange)="abrir($event)" aria-label="Tipo de movimiento">
            <mat-button-toggle value="DEPOSITO">Depósito</mat-button-toggle>
            <mat-button-toggle value="CHEQUE">Cheque</mat-button-toggle>
            <mat-button-toggle value="TRANSFERENCIA">Transferencia</mat-button-toggle>
            <mat-button-toggle value="NOTA_DEBITO">Nota de débito</mat-button-toggle>
            <mat-button-toggle value="NOTA_CREDITO">Nota de crédito</mat-button-toggle>
          </mat-button-toggle-group>
          <p class="ayuda">{{ ayuda[f] }}</p>
          <div class="grid-form">
            <mat-form-field><mat-label>{{ f === 'TRANSFERENCIA' ? 'Cuenta origen' : 'Cuenta' }}</mat-label>
              <mat-select [(ngModel)]="nuevo.cuentaId">
                @for (c of cuentas.activas(); track c.id) { <mat-option [value]="c.id">{{ etiqueta(c) }}</mat-option> }
              </mat-select></mat-form-field>
            @if (f === 'TRANSFERENCIA') {
              <mat-form-field><mat-label>Cuenta destino</mat-label><mat-select [(ngModel)]="nuevo.destinoId">
                @for (c of cuentas.activas(); track c.id) { @if (c.id !== nuevo.cuentaId) { <mat-option [value]="c.id">{{ etiqueta(c) }}</mat-option> } }
              </mat-select></mat-form-field>
            }
            <mat-form-field><mat-label>Fecha</mat-label><input matInput type="date" [(ngModel)]="nuevo.fecha" /></mat-form-field>
            <mat-form-field><mat-label>Monto</mat-label><input matInput type="number" min="0.01" [(ngModel)]="nuevo.monto" /></mat-form-field>
            <mat-form-field><mat-label>{{ f === 'CHEQUE' ? 'No. cheque (vacío = automático)' : 'Referencia' }}</mat-label>
              <input matInput [(ngModel)]="nuevo.referencia" /></mat-form-field>
            @if (f === 'CHEQUE') {
              <mat-form-field><mat-label>Beneficiario</mat-label><input matInput [(ngModel)]="nuevo.beneficiario" /></mat-form-field>
            }
            <mat-form-field class="ancho-completo"><mat-label>Concepto</mat-label><input matInput [(ngModel)]="nuevo.concepto" /></mat-form-field>
            @if (f === 'DEPOSITO') {
              <mat-checkbox class="ancho-completo" [(ngModel)]="nuevo.desdeCaja">Depositar efectivo de mi caja abierta (se registra la salida en la caja)</mat-checkbox>
            }
          </div>
          <div class="fila">
            <span class="espaciador"></span>
            <button mat-button (click)="formulario.set(null)">Cancelar</button>
            <button mat-flat-button [disabled]="!valido()" (click)="guardar()">Registrar</button>
          </div>
        </div>
      }

      <div class="tarjeta">
        <div class="fila">
          <mat-form-field style="flex:1 1 280px"><mat-label>Cuenta</mat-label>
            <mat-select [(ngModel)]="filtro.cuentaId" (selectionChange)="cargar()">
              <mat-option [value]="null">Todas</mat-option>
              @for (c of cuentas.activas(); track c.id) { <mat-option [value]="c.id">{{ etiqueta(c) }}</mat-option> }
            </mat-select></mat-form-field>
          <mat-form-field><mat-label>Tipo</mat-label><mat-select [(ngModel)]="filtro.tipo" (selectionChange)="cargar()">
            <mat-option [value]="null">Todos</mat-option>
            @for (t of tiposLista; track t[0]) { <mat-option [value]="t[0]">{{ t[1] }}</mat-option> }
          </mat-select></mat-form-field>
          <mat-form-field><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="filtro.desde" (change)="cargar()" /></mat-form-field>
          <mat-form-field><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="filtro.hasta" (change)="cargar()" /></mat-form-field>
          @if (cuentaFiltrada(); as c) { <div class="saldo">Saldo en libros <strong>{{ c.saldo | currency }}</strong></div> }
        </div>
        <div class="tabla-contenedor">
          <table mat-table [dataSource]="pagina()?.content ?? []">
            <ng-container matColumnDef="numero"><th mat-header-cell *matHeaderCellDef>No.</th><td mat-cell *matCellDef="let m">{{ m.numero }}</td></ng-container>
            <ng-container matColumnDef="fecha"><th mat-header-cell *matHeaderCellDef>Fecha</th><td mat-cell *matCellDef="let m">{{ m.fecha | date: 'dd/MM/yyyy' }}</td></ng-container>
            <ng-container matColumnDef="cuenta"><th mat-header-cell *matHeaderCellDef>Cuenta</th><td mat-cell *matCellDef="let m">{{ nombreCuenta(m.cuentaId) }}</td></ng-container>
            <ng-container matColumnDef="tipo"><th mat-header-cell *matHeaderCellDef>Tipo</th><td mat-cell *matCellDef="let m">{{ tipos[m.tipo] }}</td></ng-container>
            <ng-container matColumnDef="referencia"><th mat-header-cell *matHeaderCellDef>Ref. / Cheque</th><td mat-cell *matCellDef="let m">{{ m.referencia }}</td></ng-container>
            <ng-container matColumnDef="concepto"><th mat-header-cell *matHeaderCellDef>Concepto</th>
              <td mat-cell *matCellDef="let m">{{ m.concepto }}@if (m.beneficiario) { <div class="sub">{{ m.beneficiario }}</div> }
                <div class="sub">{{ origenes[m.origenTipo] ?? m.origenTipo }}</div></td></ng-container>
            <ng-container matColumnDef="monto"><th mat-header-cell *matHeaderCellDef class="num">Monto</th>
              <td mat-cell *matCellDef="let m" class="num" [class.negativo]="m.montoConSigno < 0">{{ m.montoConSigno | currency }}</td></ng-container>
            <ng-container matColumnDef="estado"><th mat-header-cell *matHeaderCellDef>Estado</th>
              <td mat-cell *matCellDef="let m">
                @if (m.estado === 'ANULADO') { <span class="chip" [title]="m.motivoAnulacion">Anulado</span> }
                @else if (m.conciliacionId) { <span class="chip ok">Conciliado</span> }
                @else { <span class="chip">Pendiente</span> }
              </td></ng-container>
            <ng-container matColumnDef="acciones"><th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let m">
                @if (puedeRegistrar() && m.estado === 'REGISTRADO' && !m.conciliacionId && anulableManual(m)) {
                  <button mat-button (click)="anular(m)">Anular</button>
                }
              </td></ng-container>
            <tr mat-header-row *matHeaderRowDef="columnas"></tr>
            <tr mat-row *matRowDef="let r; columns: columnas" [class.anulado]="r.estado === 'ANULADO'"></tr>
          </table>
        </div>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="50" (page)="cargar($event)" />
      </div>
    </div>`,
  styles: `.ayuda { color: var(--mat-sys-on-surface-variant); margin: 12px 0; }
    .saldo { font: var(--mat-sys-title-medium); margin-left: auto; }
    .sub { font-size: 0.85em; color: var(--mat-sys-on-surface-variant); }
    .negativo { color: var(--mat-sys-error); } .anulado td { text-decoration: line-through; opacity: .6; }`,
})
export class Movimientos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly cuentas = inject(CuentasBancarias);
  protected readonly etiqueta = CuentasBancarias.etiqueta;
  protected readonly tipos = TIPOS_MOVIMIENTO;
  protected readonly tiposLista = Object.entries(TIPOS_MOVIMIENTO);
  protected readonly origenes = ORIGENES;
  protected readonly columnas = ['numero', 'fecha', 'cuenta', 'tipo', 'referencia', 'concepto', 'monto', 'estado', 'acciones'];
  protected readonly pagina = signal<Pagina<Mov> | null>(null);
  protected readonly formulario = signal<Formulario | null>(null);
  protected readonly puedeRegistrar = computed(() => this.auth.puede('BANCO_MOVIMIENTOS'));
  protected filtro = { cuentaId: null as number | null, tipo: null as Tipo | null, desde: '', hasta: '' };
  protected nuevo = this.vacio();
  private readonly filtroCuenta = signal<number | null>(null);
  protected readonly cuentaFiltrada = computed(() => this.cuentas.activas().find((c) => c.id === this.filtroCuenta()) ?? null);
  protected readonly ayuda: Record<Formulario, string> = {
    DEPOSITO: 'Dinero que entra a la cuenta. Marque “desde mi caja” para depositar el efectivo cobrado: se registra la salida en su caja.',
    CHEQUE: 'Cheque emitido. Si deja el número vacío se toma el próximo de la cuenta; no se permiten números repetidos.',
    TRANSFERENCIA: 'Traspaso entre dos cuentas propias: genera la salida y la entrada enlazadas.',
    NOTA_DEBITO: 'Cargo del banco a la cuenta: comisiones, impuestos (0.15%), cargos por servicio.',
    NOTA_CREDITO: 'Abono del banco a la cuenta: intereses ganados, reversos, créditos directos.',
  };

  constructor() {
    this.cuentas.cargar();
    this.cargar();
  }

  protected nombreCuenta(id?: number): string {
    const c = this.cuentas.activas().find((x) => x.id === id);
    return c ? `${c.banco} · ${c.numero}` : String(id ?? '');
  }

  /** Los movimientos de pagos y recibos se anulan anulando su documento. */
  protected anulableManual(m: Mov): boolean {
    return !m.origenTipo || ['MANUAL', 'DEPOSITO_CAJA', 'TRANSFERENCIA'].includes(m.origenTipo);
  }

  protected cargar(e?: PageEvent): void {
    this.filtroCuenta.set(this.filtro.cuentaId);
    this.http.get<Pagina<Mov>>('/api/v1/banco/movimientos', { params: params({ ...this.filtro, page: e?.pageIndex ?? 0, size: 50 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected abrir(f: Formulario): void {
    const cuentaId = this.nuevo.cuentaId ?? this.filtro.cuentaId ?? this.cuentas.activas()[0]?.id ?? null;
    if (this.formulario() === null) this.nuevo = { ...this.vacio(), cuentaId };
    this.formulario.set(f);
  }

  protected valido(): boolean {
    const n = this.nuevo;
    return !!n.cuentaId && n.monto > 0 && !!n.concepto.trim() && (this.formulario() !== 'TRANSFERENCIA' || (!!n.destinoId && n.destinoId !== n.cuentaId));
  }

  protected guardar(): void {
    const f = this.formulario()!, n = this.nuevo;
    const fin = (m: Mov) => {
      this.notif.ok(f === 'CHEQUE' ? `Cheque No. ${m.referencia} registrado` : `Movimiento ${m.numero} registrado`);
      this.formulario.set(null); this.cuentas.cargar(); this.cargar();
    };
    if (f === 'TRANSFERENCIA') {
      const req: S['MovimientoBancarioTransferenciaRequest'] = { cuentaOrigenId: n.cuentaId!, cuentaDestinoId: n.destinoId!,
        fecha: n.fecha || undefined, monto: n.monto, concepto: n.concepto, referencia: n.referencia || undefined };
      this.http.post<Mov>('/api/v1/banco/transferencias', req).subscribe({ next: fin, error: (e) => this.notif.error(e) });
      return;
    }
    const req: S['MovimientoBancarioRegistrarRequest'] = { cuentaId: n.cuentaId!, tipo: f, fecha: n.fecha || undefined,
      referencia: n.referencia || undefined, beneficiario: f === 'CHEQUE' ? n.beneficiario || undefined : undefined,
      concepto: n.concepto, monto: n.monto, desdeCaja: f === 'DEPOSITO' && n.desdeCaja };
    this.http.post<Mov>('/api/v1/banco/movimientos', req).subscribe({ next: fin, error: (e) => this.notif.error(e) });
  }

  protected anular(m: Mov): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Anular movimiento ${m.numero}`, accion: 'Anular', pedirMotivo: 'Motivo de la anulación', peligro: true,
      mensaje: m.transferenciaParId ? 'Se anulará también el movimiento enlazado de la otra cuenta.' : 'El saldo de la cuenta se restituye.' } })
      .afterClosed().subscribe((r) => {
        if (!r) return;
        const motivo = typeof r === 'string' ? r : 'Anulado por el usuario';
        this.http.post<Mov>(`/api/v1/banco/movimientos/${m.id}/anular`, { motivo }).subscribe({
          next: () => { this.notif.ok('Movimiento anulado'); this.cuentas.cargar(); this.cargar(); }, error: (e) => this.notif.error(e) });
      });
  }

  private vacio() {
    return { cuentaId: null as number | null, destinoId: null as number | null, fecha: '', monto: 0, referencia: '',
      beneficiario: '', concepto: '', desdeCaja: false };
  }
}
