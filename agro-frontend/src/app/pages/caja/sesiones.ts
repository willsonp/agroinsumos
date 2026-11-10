import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import type { Pagina, S } from '../../api/tipos';
import { params } from '../../shared/http';
import { SesionDetalle } from './sesion-detalle';

/** Supervisión de cajas: listado y arqueo de cada sesión. */
@Component({
  selector: 'app-sesiones',
  imports: [CurrencyPipe, DatePipe, MatTableModule, MatPaginatorModule, MatButtonModule, SesionDetalle],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Supervisión de cajas</h1></div>
      <div class="tarjeta tabla-contenedor">
        <table mat-table [dataSource]="pagina()?.content ?? []">
          <ng-container matColumnDef="numero"><th mat-header-cell *matHeaderCellDef>Caja</th><td mat-cell *matCellDef="let s">#{{ s.numero }}</td></ng-container>
          <ng-container matColumnDef="usuario"><th mat-header-cell *matHeaderCellDef>Usuario</th><td mat-cell *matCellDef="let s">{{ s.usuarioId }}</td></ng-container>
          <ng-container matColumnDef="abierta"><th mat-header-cell *matHeaderCellDef>Apertura</th><td mat-cell *matCellDef="let s">{{ s.abiertaEn | date: 'short' }}</td></ng-container>
          <ng-container matColumnDef="cerrada"><th mat-header-cell *matHeaderCellDef>Cierre</th><td mat-cell *matCellDef="let s">{{ s.cerradaEn | date: 'short' }}</td></ng-container>
          <ng-container matColumnDef="esperado"><th mat-header-cell *matHeaderCellDef class="num">Esperado</th><td mat-cell *matCellDef="let s" class="num">{{ s.cuadre?.efectivoEsperado | currency }}</td></ng-container>
          <ng-container matColumnDef="diferencia"><th mat-header-cell *matHeaderCellDef class="num">Diferencia</th>
            <td mat-cell *matCellDef="let s" class="num" [class.error-texto]="(s.cuadre?.diferencia ?? 0) !== 0">{{ s.cuadre?.diferencia | currency }}</td></ng-container>
          <ng-container matColumnDef="estado"><th mat-header-cell *matHeaderCellDef>Estado</th>
            <td mat-cell *matCellDef="let s"><span class="chip" [class.ok]="s.estado === 'ABIERTA'">{{ s.estado }}</span></td></ng-container>
          <tr mat-header-row *matHeaderRowDef="columnas"></tr>
          <tr mat-row *matRowDef="let s; columns: columnas" class="clickable" (click)="ver(s)"></tr>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
      </div>
      @if (detalle(); as d) {
        <div class="tarjeta" style="margin-top:16px">
          <div class="fila"><h2 style="flex:1">Arqueo caja #{{ d.sesion?.numero }}</h2>
            <button mat-button (click)="detalle.set(null)">Cerrar</button></div>
          <app-sesion-detalle [sesion]="d.sesion!" [movimientos]="d.movimientos ?? []" />
        </div>
      }
    </div>`,
})
export class Sesiones {
  private readonly http = inject(HttpClient);
  protected readonly columnas = ['numero', 'usuario', 'abierta', 'cerrada', 'esperado', 'diferencia', 'estado'];
  protected readonly pagina = signal<Pagina<S['CajaSesionResponse']> | null>(null);
  protected readonly detalle = signal<S['CajaSesionDetalle'] | null>(null);

  constructor() { this.cargar(); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<S['CajaSesionResponse']>>('/api/v1/caja/sesiones', { params: params({ page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe((p) => this.pagina.set(p));
  }

  protected ver(s: S['CajaSesionResponse']): void {
    this.http.get<S['CajaSesionDetalle']>(`/api/v1/caja/sesiones/${s.id}`).subscribe((d) => this.detalle.set(d));
  }
}
