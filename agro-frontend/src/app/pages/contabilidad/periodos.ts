import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

/** Cierre mensual de la contabilidad (un mes cerrado no admite asientos). */
@Component({
  selector: 'app-periodos',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Cierre de períodos</h1></div>
      <div class="tarjeta">
        <p class="ayuda">Antes de cerrar un mes, genere los asientos automáticos y revise la balanza. Un mes cerrado no admite asientos
          nuevos, reversos ni anulaciones; el administrador puede reabrirlo.</p>
        <mat-form-field><mat-label>Año</mat-label><input matInput type="number" [ngModel]="anio()" (ngModelChange)="anio.set(+$event); previa.set(null)" /></mat-form-field>
        <table class="tabla">
          <thead><tr><th>Mes</th><th>Estado</th><th>Cerrado el</th><th></th></tr></thead>
          <tbody>
            @for (m of meses(); track m.mes) {
              <tr><td>{{ m.nombre }}</td>
                <td>@if (m.cerrado) { <span class="chip">Cerrado</span> } @else { <span class="chip ok">Abierto</span> }</td>
                <td>{{ m.cerrado?.cerradoEn | date: 'dd/MM/yyyy HH:mm' }}</td>
                <td>@if (esAdmin()) {
                  @if (m.cerrado) { <button mat-button (click)="cambiar(m.mes, false)">Reabrir</button> }
                  @else { <button mat-button (click)="cambiar(m.mes, true)">Cerrar</button> }
                }</td></tr>
            }
          </tbody>
        </table>
      </div>

      <h2>Cierre del ejercicio {{ anio() }}</h2>
      <div class="tarjeta">
        <p class="ayuda">Asiento al 31 de diciembre que salda los ingresos, costos y gastos del año contra Resultados acumulados.
          Después no se aceptan asientos con fecha de ese año. Genere antes los asientos automáticos pendientes.</p>
        @if (cierreVigente(); as c) {
          <p><span class="chip">Ejercicio cerrado</span> Asiento {{ c.numero }} del {{ c.fecha | date: 'dd/MM/yyyy' }} por {{ c.total | currency }}.</p>
          @if (esAdmin()) { <button mat-stroked-button (click)="anularCierre()">Reabrir ejercicio</button> }
        } @else if (esAdmin()) {
          <div class="fila">
            <button mat-stroked-button (click)="cierre(true)">Vista previa</button>
            <button mat-flat-button [disabled]="!previa() || (previa()!.documentosPendientes ?? 0) > 0" (click)="cierre(false)">Cerrar ejercicio</button>
          </div>
          @if (previa(); as r) {
            <table class="tabla resumen"><tbody>
              <tr><td>Ingresos</td><td class="num">{{ r.ingresos | currency }}</td></tr>
              <tr><td>Costos</td><td class="num">{{ r.costos | currency }}</td></tr>
              <tr><td>Gastos</td><td class="num">{{ r.gastos | currency }}</td></tr>
              <tr class="total"><td>{{ (r.resultado ?? 0) >= 0 ? 'Utilidad' : 'Pérdida' }} a {{ r.cuentaDestino }}</td><td class="num">{{ r.resultado | currency }}</td></tr>
            </tbody></table>
            @for (a of r.advertencias; track $index) { <p class="error-texto">{{ a }}</p> }
          }
        } @else { <p>El ejercicio está abierto.</p> }
        @if (historialAnio().length) {
          <p class="ayuda">Historial: @for (c of historialAnio(); track c.asientoId) { asiento {{ c.numero }} ({{ c.estado === 'ANULADO' ? 'anulado: ' + c.motivoAnulacion : 'vigente' }}); }</p>
        }
      </div>
    </div>`,
  styles: `.tabla { width: 100%; max-width: 720px; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .ayuda { color: var(--mat-sys-on-surface-variant); } .tabla .num { text-align: right; } .resumen { max-width: 520px; margin-top: 12px; }
    .total td { font-weight: 600; }`,
})
export class Periodos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly esAdmin = this.auth.esAdmin;
  protected readonly anio = signal(new Date().getFullYear());
  private readonly cerrados = signal<S['PeriodoContablePeriodo'][]>([]);
  protected readonly meses = computed(() => MESES.map((nombre, i) => ({ mes: i + 1, nombre,
    cerrado: this.cerrados().find((p) => p.anio === this.anio() && p.mes === i + 1) })));

  private readonly cierres = signal<S['CierreAnualCierre'][]>([]);
  protected readonly previa = signal<S['CierreAnualResultado'] | null>(null);
  protected readonly historialAnio = computed(() => this.cierres().filter((c) => c.anio === this.anio()));
  protected readonly cierreVigente = computed(() => this.historialAnio().find((c) => c.estado === 'REGISTRADO') ?? null);

  constructor() { this.cargar(); }

  protected cierre(simular: boolean): void {
    const ejecutar = () => this.http.post<S['CierreAnualResultado']>(`/api/v1/contabilidad/cierres-anuales/${this.anio()}`, {},
      { params: { simular } }).subscribe({
      next: (r) => { this.previa.set(simular ? r : null); if (!simular) { this.notif.ok(`Ejercicio ${this.anio()} cerrado`); this.cargar(); } },
      error: (e) => { this.previa.set(null); this.notif.error(e); } });
    if (simular) { ejecutar(); return; }
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Cerrar el ejercicio ${this.anio()}`, accion: 'Cerrar ejercicio',
      mensaje: 'Se registrará el asiento de cierre al 31 de diciembre y no se aceptarán más asientos con fecha de ese año.' } })
      .afterClosed().subscribe((ok) => ok && ejecutar());
  }

  protected anularCierre(): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: `Reabrir el ejercicio ${this.anio()}`, accion: 'Reabrir', peligro: true,
      pedirMotivo: 'Motivo', mensaje: 'Se anula el asiento de cierre; luego deberá cerrar el ejercicio otra vez.' } })
      .afterClosed().subscribe((motivo) => motivo && this.http.post(`/api/v1/contabilidad/cierres-anuales/${this.anio()}/anular`, { motivo })
        .subscribe({ next: () => { this.notif.ok('Ejercicio reabierto'); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }

  private cargar(): void {
    this.http.get<S['CierreAnualCierre'][]>('/api/v1/contabilidad/cierres-anuales').subscribe({ next: (c) => this.cierres.set(c), error: () => this.cierres.set([]) });
    this.http.get<S['PeriodoContablePeriodo'][]>('/api/v1/contabilidad/periodos').subscribe({
      next: (p) => this.cerrados.set(p), error: (e) => this.notif.error(e) });
  }

  protected cambiar(mes: number, cerrar: boolean): void {
    const accion = cerrar ? 'cerrar' : 'reabrir';
    this.dialog.open(ConfirmarDialog, { data: { titulo: `${cerrar ? 'Cerrar' : 'Reabrir'} ${MESES[mes - 1]} ${this.anio()}`, accion: cerrar ? 'Cerrar' : 'Reabrir',
      mensaje: cerrar ? 'No se podrán registrar ni anular asientos con fecha en este mes.' : 'Se podrán volver a registrar asientos en este mes.' } })
      .afterClosed().subscribe((ok) => ok && this.http.post(`/api/v1/contabilidad/periodos/${this.anio()}/${mes}/${accion}`, {}).subscribe({
        next: () => { this.notif.ok(`Período ${cerrar ? 'cerrado' : 'reabierto'}`); this.cargar(); }, error: (e) => this.notif.error(e) }));
  }
}
