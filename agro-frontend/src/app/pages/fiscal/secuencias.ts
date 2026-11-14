import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { ComprobanteService } from '../../core/comprobante.service';
import { Notificacion } from '../../core/notificacion.service';

const TIPOS: [number, string][] = [[31, 'Crédito fiscal'], [32, 'Consumo'], [33, 'Nota de débito'], [34, 'Nota de crédito'],
  [41, 'Compras'], [43, 'Gastos menores'], [44, 'Regímenes especiales'], [45, 'Gubernamental']];

/** Rangos de comprobantes (e-NCF serie E o NCF serie B) autorizados por la DGII (Oficina Virtual > Solicitud de secuencias). */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-secuencias',
  imports: [Fecha, DatePipe, DecimalPipe, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Secuencias de comprobantes</h1></div>
      @if (auth.esAdmin()) {
        <form class="tarjeta" [formGroup]="form" (ngSubmit)="registrar()">
          <h3>Registrar rango autorizado</h3>
          <div class="fila">
            <mat-form-field><mat-label>Serie</mat-label><mat-select formControlName="serie">
              <mat-option value="E">E · e-CF</mat-option><mat-option value="B">B · NCF tradicional</mat-option></mat-select></mat-form-field>
            <mat-form-field><mat-label>Tipo</mat-label><mat-select formControlName="tipoEcf">
              @for (t of tipos; track t[0]) { <mat-option [value]="t[0]">{{ prefijo(form.controls.serie.value, t[0]) }} · {{ t[1] }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Desde</mat-label><input matInput type="number" formControlName="secuenciaDesde" /></mat-form-field>
            <mat-form-field><mat-label>Hasta</mat-label><input matInput type="number" formControlName="secuenciaHasta" /></mat-form-field>
            <app-fecha etiqueta="Vence" formControlName="fechaVencimiento" />
            <button mat-flat-button [disabled]="form.invalid">Registrar</button>
          </div>
          <p class="muted">Registrar un rango nuevo reemplaza al rango activo del mismo tipo y serie. Modo actual de la empresa: <strong>{{ modoTexto() }}</strong> (Administración → Empresa).</p>
        </form>
      }
      <div class="tarjeta" style="margin-top:16px">
        <table class="tabla">
          <tr><th>Tipo</th><th class="num">Desde</th><th class="num">Hasta</th><th class="num">Última usada</th><th class="num">Disponibles</th><th>Vence</th><th>Estado</th></tr>
          @for (s of secuencias(); track s.id) {
            <tr [class.muted]="!s.activa"><td>{{ s.prefijo }} · {{ nombre(s.tipoEcf) }}</td><td class="num">{{ s.secuenciaDesde }}</td><td class="num">{{ s.secuenciaHasta }}</td>
              <td class="num">{{ s.ultimaUsada }}</td>
              <td class="num" [class.error-texto]="s.activa && (s.disponibles ?? 0) < 100">{{ s.disponibles | number }}</td>
              <td>{{ s.fechaVencimiento | date: 'dd/MM/yyyy' }}</td>
              <td><span class="chip" [class.ok]="s.activa">{{ s.activa ? 'Activa' : 'Reemplazada' }}</span></td></tr>
          } @empty { <tr><td colspan="7" class="muted">No hay secuencias registradas: no se podrán emitir comprobantes</td></tr> }
        </table>
      </div>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }`,
})
export class Secuencias {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly tipos = TIPOS;
  protected readonly secuencias = signal<S['EcfSecuenciaResponse'][]>([]);
  private readonly comp = inject(ComprobanteService);
  protected readonly modoTexto = computed(() => ({ ECF: 'e-CF (serie E)', NCF: 'NCF tradicional (serie B)', NINGUNO: 'sin comprobante fiscal' })[this.comp.modo()]);
  protected readonly form = inject(NonNullableFormBuilder).group({
    serie: [this.comp.modo() === 'NCF' ? 'B' : 'E', Validators.required],
    tipoEcf: [32, Validators.required], secuenciaDesde: [1, [Validators.required, Validators.min(1)]],
    secuenciaHasta: [1000, [Validators.required, Validators.min(1)]], fechaVencimiento: ['', Validators.required],
  });

  constructor() { this.cargar(); }

  protected prefijo(serie: string, t: number): string { return serie === 'B' ? 'B' + String(t - 30).padStart(2, '0') : 'E' + t; }

  protected nombre(t?: number): string { return TIPOS.find((x) => x[0] === t)?.[1] ?? ''; }

  private cargar(): void {
    this.http.get<S['EcfSecuenciaResponse'][]>('/api/v1/ecf/secuencias').subscribe((s) => this.secuencias.set([...s].sort((a, b) => (a.prefijo ?? '').localeCompare(b.prefijo ?? '') || (a.id ?? 0) - (b.id ?? 0))));
  }

  protected registrar(): void {
    this.http.post('/api/v1/ecf/secuencias', this.form.getRawValue()).subscribe({
      next: () => { this.notif.ok('Secuencia registrada'); this.cargar(); }, error: (e) => this.notif.error(e) });
  }
}
