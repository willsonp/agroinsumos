import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';

/** Datos de la empresa para etiquetas, impresos y reportes. */
@Component({
  selector: 'app-empresa',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Datos de la empresa</h1></div>
      @if (datos(); as d) {
        <div class="tarjeta">
          <div class="grid-form">
            <mat-form-field class="ancho-completo"><mat-label>Razón social</mat-label><input matInput [(ngModel)]="d.nombre" maxlength="150" /></mat-form-field>
            <mat-form-field><mat-label>Nombre comercial (etiquetas e impresos)</mat-label><input matInput [(ngModel)]="d.nombreComercial" maxlength="150" /></mat-form-field>
            <mat-form-field><mat-label>RNC</mat-label><input matInput [value]="d.rnc" disabled />
              <mat-hint>Lo usan los e-CF; se cambia con la habilitación de la DGII</mat-hint></mat-form-field>
            <mat-form-field class="ancho-completo"><mat-label>Dirección</mat-label><input matInput [(ngModel)]="d.direccion" maxlength="200" /></mat-form-field>
            <mat-form-field><mat-label>Teléfono</mat-label><input matInput [(ngModel)]="d.telefono" maxlength="30" /></mat-form-field>
            <mat-form-field><mat-label>Correo</mat-label><input matInput type="email" [(ngModel)]="d.correo" maxlength="100" /></mat-form-field>
            <mat-form-field><mat-label>Sitio web</mat-label><input matInput [(ngModel)]="d.sitioWeb" maxlength="100" /></mat-form-field>
          </div>
          <div class="fila"><span class="espaciador"></span><button mat-flat-button [disabled]="!d.nombre" (click)="guardar(d)">Guardar</button></div>
        </div>
      }
    </div>`,
})
export class Empresa {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly datos = signal<S['CompaniaResponse'] | null>(null);

  constructor() { this.http.get<S['CompaniaResponse']>('/api/v1/admin/empresa').subscribe((d) => this.datos.set({ ...d })); }

  protected guardar(d: S['CompaniaResponse']): void {
    const req: S['CompaniaRequest'] = { nombre: d.nombre!, nombreComercial: d.nombreComercial || undefined, direccion: d.direccion || undefined,
      telefono: d.telefono || undefined, correo: d.correo || undefined, sitioWeb: d.sitioWeb || undefined };
    this.http.put<S['CompaniaResponse']>('/api/v1/admin/empresa', req).subscribe({
      next: (r) => { this.datos.set({ ...r }); this.notif.ok('Datos de la empresa guardados'); }, error: (e) => this.notif.error(e) });
  }
}
