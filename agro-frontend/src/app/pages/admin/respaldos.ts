import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { descargar } from '../../shared/http';

/** Respaldos diarios de la base de datos (estructura y datos) en BK_<base>_<día>.sql. */
@Component({
  selector: 'app-respaldos',
  imports: [DatePipe, DecimalPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Respaldos de la base de datos</h1></div>
      @if (estado(); as e) {
        <div class="tarjeta">
          <p>
            @if (e.diarioAutomatico) { <span class="chip ok">Respaldo diario automático activo</span> }
            @else { <span class="chip error">Respaldo automático desactivado</span> }
            @if (e.respaldoDeHoy) { <span class="chip ok">Respaldo de hoy listo</span> } @else { <span class="chip">Pendiente el de hoy</span> }
          </p>
          <p>Cada hora el servidor verifica si ya existe el respaldo del día y, si falta, lo genera. El archivo de hoy es
            <strong>{{ e.proximoNombre }}</strong>: el número es el día del mes, así se guardan hasta 31 respaldos y cada uno se
            reemplaza el mismo día del mes siguiente (como en el sistema anterior).</p>
          <div class="fila">
            <mat-form-field style="flex:1 1 420px"><mat-label>Carpeta del servidor donde se guardan</mat-label>
              <input matInput [(ngModel)]="directorio" /><mat-hint>Ruta absoluta, p.ej. /respaldos o D:\\Respaldos\\Agro (una unidad externa o de red es lo ideal)</mat-hint></mat-form-field>
            <button mat-stroked-button [disabled]="!directorio || directorio === e.directorio" (click)="guardarDirectorio()">Cambiar carpeta</button>
            <span class="espaciador"></span>
            <button mat-flat-button [disabled]="generando()" (click)="crear()"><span class="material-symbols-outlined">backup</span>
              {{ generando() ? 'Generando…' : 'Crear respaldo ahora' }}</button>
          </div>
        </div>
        <div class="tarjeta">
          <table class="tabla">
            <thead><tr><th>Archivo</th><th>Fecha</th><th class="num">Tamaño</th><th></th></tr></thead>
            <tbody>
              @for (a of e.respaldos; track a.nombre) {
                <tr><td>{{ a.nombre }}</td><td>{{ a.modificado | date: 'dd/MM/yyyy hh:mm a' }}</td><td class="num">{{ (a.bytes ?? 0) / 1048576 | number: '1.1-1' }} MB</td>
                  <td><button mat-button (click)="bajar(a.nombre!)"><span class="material-symbols-outlined">download</span> Descargar</button></td></tr>
              } @empty { <tr><td colspan="4" class="muted">Aún no hay respaldos en {{ e.directorio }}</td></tr> }
            </tbody>
          </table>
          <p class="muted">Restaurar (con el sistema detenido): <code>psql -d {{ e.baseDatos }} -f {{ e.proximoNombre }}</code>.
            El archivo incluye la estructura y los datos y borra los objetos existentes antes de recrearlos.</p>
        </div>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; } .chip { margin-right: 6px; }`,
})
export class Respaldos {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly estado = signal<S['RespaldoEstado'] | null>(null);
  protected readonly generando = signal(false);
  protected directorio = '';

  constructor() { this.cargar(); }

  private cargar(): void {
    this.http.get<S['RespaldoEstado']>('/api/v1/admin/respaldos').subscribe({
      next: (e) => { this.estado.set(e); this.directorio = e.directorio ?? ''; }, error: (err) => this.notif.error(err) });
  }

  protected crear(): void {
    this.generando.set(true);
    this.http.post<S['RespaldoResultado']>('/api/v1/admin/respaldos', {}).subscribe({
      next: (r) => { this.generando.set(false); this.notif.ok(`Respaldo ${r.nombre} creado (${((r.bytes ?? 0) / 1048576).toFixed(1)} MB)`); this.cargar(); },
      error: (e) => { this.generando.set(false); this.notif.error(e); } });
  }

  protected guardarDirectorio(): void {
    this.http.put<S['RespaldoEstado']>('/api/v1/admin/respaldos/directorio', { directorio: this.directorio }).subscribe({
      next: (e) => { this.estado.set(e); this.notif.ok('Carpeta de respaldos actualizada'); }, error: (err) => this.notif.error(err) });
  }

  protected bajar(nombre: string): void {
    this.http.get(`/api/v1/admin/respaldos/${nombre}`, { responseType: 'blob' })
      .subscribe({ next: (b) => descargar(b, nombre), error: (e) => this.notif.error(e) });
  }
}
