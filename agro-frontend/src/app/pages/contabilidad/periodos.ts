import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
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
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Cierre de períodos</h1></div>
      <div class="tarjeta">
        <p class="ayuda">Antes de cerrar un mes, genere los asientos automáticos y revise la balanza. Un mes cerrado no admite asientos
          nuevos, reversos ni anulaciones; el administrador puede reabrirlo.</p>
        <mat-form-field><mat-label>Año</mat-label><input matInput type="number" [ngModel]="anio()" (ngModelChange)="anio.set(+$event)" /></mat-form-field>
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
    </div>`,
  styles: `.tabla { width: 100%; max-width: 720px; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .ayuda { color: var(--mat-sys-on-surface-variant); }`,
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

  constructor() { this.cargar(); }

  private cargar(): void {
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
