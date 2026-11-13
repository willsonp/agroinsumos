import { Component, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { CierreDialog, EgresoDialog } from './caja-dialogs';
import { CierreImpreso } from './cierre-impreso';
import { SesionDetalle } from './sesion-detalle';

@Component({
  selector: 'app-mi-caja',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, SesionDetalle],
  template: `
    <div class="pagina">
      @if (cargado()) {
        @if (sesion(); as s) {
          <div class="pagina-titulo">
            <h1>Caja #{{ s.numero }} <span class="muted">· abierta {{ s.abiertaEn | date: 'short' }}</span></h1>
            <button mat-stroked-button (click)="movimiento(true)"><span class="material-symbols-outlined">add</span> Ingreso</button>
            <button mat-stroked-button (click)="movimiento(false)"><span class="material-symbols-outlined">remove</span> Egreso</button>
            <button mat-stroked-button (click)="dialog.open(resumen, { data: { sesionId: s.id } })"><span class="material-symbols-outlined">receipt_long</span> Resumen</button>
            <button mat-flat-button (click)="cerrar()"><span class="material-symbols-outlined">lock</span> Cerrar caja</button>
          </div>
          <div class="tarjeta"><app-sesion-detalle [sesion]="s" [movimientos]="movimientos()" /></div>
        } @else {
          <div class="pagina-titulo"><h1>Mi caja</h1></div>
          <div class="tarjeta abrir">
            <p>No tiene una caja abierta. Indique el efectivo con que inicia el turno.</p>
            <div class="fila">
              <mat-form-field><mat-label>Monto inicial</mat-label><input matInput type="number" min="0" [(ngModel)]="montoInicial" /></mat-form-field>
              <button mat-flat-button (click)="abrir()">Abrir caja</button>
            </div>
          </div>
        }
      }
    </div>`,
  styles: `.abrir { max-width: 520px; }`,
})
export class MiCaja {
  private readonly http = inject(HttpClient);
  protected readonly dialog = inject(MatDialog);
  protected readonly resumen = CierreImpreso;
  private readonly notif = inject(Notificacion);
  protected readonly sesion = signal<S['CajaSesionResponse'] | null>(null);
  protected readonly movimientos = signal<S['CajaMovimientoResponse'][]>([]);
  protected readonly cargado = signal(false);
  protected montoInicial = 0;

  constructor() { this.cargar(); }

  private cargar(): void {
    // 204 (cuerpo null) = el usuario no tiene caja abierta
    this.http.get<S['CajaSesionResponse'] | null>('/api/v1/caja/sesiones/actual').subscribe({
      next: (s) => {
        this.sesion.set(s);
        this.cargado.set(true);
        if (s) this.http.get<S['CajaSesionDetalle']>(`/api/v1/caja/sesiones/${s.id}`).subscribe((d) => this.movimientos.set(d.movimientos ?? []));
      },
      error: (e: HttpErrorResponse) => { this.cargado.set(true); this.notif.error(e); },
    });
  }

  protected abrir(): void {
    this.http.post('/api/v1/caja/sesiones/abrir', { montoInicial: this.montoInicial || 0 }).subscribe({
      next: () => { this.notif.ok('Caja abierta'); this.cargar(); },
      error: (e) => this.notif.error(e),
    });
  }

  protected movimiento(ingreso: boolean): void {
    this.dialog.open(EgresoDialog, { data: { ingreso } }).afterClosed().subscribe((req) => {
      if (!req) return;
      this.http.post(`/api/v1/caja/sesiones/actual/${ingreso ? 'ingresos' : 'egresos'}`, req).subscribe({
        next: () => { this.notif.ok(ingreso ? 'Ingreso registrado' : 'Egreso registrado'); this.cargar(); },
        error: (e) => this.notif.error(e),
      });
    });
  }

  protected cerrar(): void {
    this.http.get<number[]>('/api/v1/caja/denominaciones').subscribe((denominaciones) => {
      this.dialog.open(CierreDialog, { data: { denominaciones, sesionId: this.sesion()!.id! }, width: 'min(1100px, 96vw)', maxWidth: '96vw' })
        .afterClosed().subscribe((req) => {
          if (!req) return;
          this.http.post<S['CajaSesionResponse']>('/api/v1/caja/sesiones/actual/cerrar', req).subscribe({
            next: (s) => {
              this.notif.ok('Caja cerrada');
              this.cargar();
              this.dialog.open(CierreImpreso, { data: { sesionId: s.id } });   // cuadre con desglose de monedas para imprimir
            },
            error: (e) => this.notif.error(e),
          });
        });
    });
  }
}
