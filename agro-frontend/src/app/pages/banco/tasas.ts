import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { isoFecha } from '../../shared/http';
import { MONEDAS } from '../../shared/monedas';

type Tasa = S['TasaCambioTasa'];

/** Tasas de cambio (pesos por unidad). Se usan en compras, pagos y cuentas bancarias en otra moneda. */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-tasas',
  imports: [Fecha, DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Tasas de cambio</h1></div>
      <div class="tarjeta">
        <p class="ayuda">Registre la tasa del día (por ejemplo, la del Banco Central). Un documento usa la última tasa registrada
          en su fecha o antes, salvo que se indique otra al registrarlo.</p>
        <div class="fila">
          <mat-form-field><mat-label>Moneda</mat-label><mat-select [(ngModel)]="moneda">
            @for (m of monedas; track m) { <mat-option [value]="m">{{ m }}</mat-option> }</mat-select></mat-form-field>
          <app-fecha etiqueta="Fecha" [(ngModel)]="fecha" />
          <mat-form-field><mat-label>Tasa (RD$ por unidad)</mat-label><input matInput type="number" min="0" step="0.0001" [(ngModel)]="tasa" /></mat-form-field>
          <button mat-flat-button [disabled]="!tasa || tasa <= 0" (click)="guardar()">Guardar</button>
        </div>
      </div>
      <div class="tarjeta">
        <table class="tabla">
          <thead><tr><th>Fecha</th><th>Moneda</th><th class="num">Tasa</th><th></th></tr></thead>
          <tbody>
            @for (t of tasas(); track $index) {
              <tr><td>{{ t.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ t.moneda }}</td><td class="num">{{ t.tasa | number: '1.4-4' }}</td>
                <td><button mat-button (click)="eliminar(t)">Quitar</button></td></tr>
            } @empty { <tr><td colspan="4">Sin tasas registradas.</td></tr> }
          </tbody>
        </table>
      </div>
    </div>`,
  styles: `.tabla { width: 100%; max-width: 640px; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .ayuda { color: var(--mat-sys-on-surface-variant); }`,
})
export class Tasas {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly monedas = MONEDAS.filter((m) => m !== 'DOP');
  protected readonly tasas = signal<Tasa[]>([]);
  protected moneda = 'USD';
  protected fecha = isoFecha(new Date())!;
  protected tasa: number | null = null;

  constructor() { this.cargar(); }

  private cargar(): void {
    this.http.get<Tasa[]>('/api/v1/monedas/tasas').subscribe({ next: (t) => this.tasas.set(t), error: (e) => this.notif.error(e) });
  }

  protected guardar(): void {
    this.http.post<Tasa>('/api/v1/monedas/tasas', { moneda: this.moneda, fecha: this.fecha, tasa: this.tasa }).subscribe({
      next: (t) => { this.notif.ok(`Tasa ${t.moneda} ${t.tasa} registrada`); this.tasa = null; this.cargar(); }, error: (e) => this.notif.error(e) });
  }

  protected eliminar(t: Tasa): void {
    this.http.delete(`/api/v1/monedas/tasas/${t.moneda}/${t.fecha}`).subscribe({
      next: () => { this.notif.ok('Tasa eliminada'); this.cargar(); }, error: (e) => this.notif.error(e) });
  }
}
