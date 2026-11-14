import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatButtonModule } from '@angular/material/button';
import type { Pagina, S } from '../../api/tipos';
import { fechaRd, isoFecha, params, traerTodo } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';

import { exportarExcel, subtitulosEmpresa } from '../../shared/excel';
import { AuthService } from '../../core/auth.service';
@Component({
  selector: 'app-facturas',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatDatepickerModule, MatButtonModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Facturas</h1></div>
      <div class="tarjeta">
        <div class="fila">
          <mat-form-field><mat-label>Número</mat-label><input matInput type="number" [(ngModel)]="numero" (keyup.enter)="buscar(0)" /></mat-form-field>
          <mat-form-field><mat-label>Desde</mat-label><input matInput [matDatepicker]="d1" [(ngModel)]="desde" />
            <mat-datepicker-toggle matIconSuffix [for]="d1" /><mat-datepicker #d1 /></mat-form-field>
          <mat-form-field><mat-label>Hasta</mat-label><input matInput [matDatepicker]="d2" [(ngModel)]="hasta" />
            <mat-datepicker-toggle matIconSuffix [for]="d2" /><mat-datepicker #d2 /></mat-form-field>
          <mat-form-field><mat-label>Estado</mat-label>
            <mat-select [(ngModel)]="estado"><mat-option [value]="null">Todos</mat-option>
              <mat-option value="EMITIDA">Emitida (pendiente)</mat-option><mat-option value="PAGADA">Pagada</mat-option>
              <mat-option value="ANULADA">Anulada</mat-option></mat-select></mat-form-field>
          <button mat-flat-button (click)="buscar(0)">Buscar</button>
          <button mat-stroked-button [disabled]="exportando()" (click)="excel()"><span class="material-symbols-outlined">table_view</span> Excel</button>
        </div>
        <div class="tabla-contenedor">
          <table mat-table [dataSource]="pagina()?.content ?? []">
            <ng-container matColumnDef="numero"><th mat-header-cell *matHeaderCellDef>No.</th><td mat-cell *matCellDef="let f">{{ f.numero }}</td></ng-container>
            <ng-container matColumnDef="fecha"><th mat-header-cell *matHeaderCellDef>Fecha</th><td mat-cell *matCellDef="let f">{{ f.fecha | date: 'dd/MM/yyyy' }}</td></ng-container>
            <ng-container matColumnDef="cliente"><th mat-header-cell *matHeaderCellDef>Cliente</th><td mat-cell *matCellDef="let f">{{ f.clienteNombre }}</td></ng-container>
            <ng-container matColumnDef="encf"><th mat-header-cell *matHeaderCellDef>NCF</th><td mat-cell *matCellDef="let f">{{ f.encf }}</td></ng-container>
            <ng-container matColumnDef="condicion"><th mat-header-cell *matHeaderCellDef>Condición</th><td mat-cell *matCellDef="let f">{{ f.condicion }}</td></ng-container>
            <ng-container matColumnDef="total"><th mat-header-cell *matHeaderCellDef class="num">Total</th><td mat-cell *matCellDef="let f" class="num">{{ f.total | currency }}</td></ng-container>
            <ng-container matColumnDef="balance"><th mat-header-cell *matHeaderCellDef class="num">Balance</th><td mat-cell *matCellDef="let f" class="num">{{ f.balance | currency }}</td></ng-container>
            <ng-container matColumnDef="estado"><th mat-header-cell *matHeaderCellDef>Estado</th>
              <td mat-cell *matCellDef="let f"><span class="chip" [class.ok]="f.estado === 'PAGADA'" [class.error]="f.estado === 'ANULADA'"
                [class.warn]="f.estado === 'EMITIDA'">{{ f.estado }}</span></td></ng-container>
            <tr mat-header-row *matHeaderRowDef="columnas"></tr>
            <tr mat-row *matRowDef="let f; columns: columnas" class="clickable" (click)="abrir(f)"></tr>
            <tr *matNoDataRow><td [attr.colspan]="columnas.length" class="muted" style="padding:24px">Sin resultados</td></tr>
          </table>
        </div>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" [pageIndex]="pagina()?.number ?? 0"
                       (page)="cambiarPagina($event)" />
      </div>
    </div>`,
})
export class Facturas {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly notif = inject(Notificacion);
  protected readonly columnas = ['numero', 'fecha', 'cliente', 'encf', 'condicion', 'total', 'balance', 'estado'];
  protected readonly pagina = signal<Pagina<S['FacturaResumen']> | null>(null);
  protected numero: number | null = null;
  protected desde: Date | null = null;
  protected hasta: Date | null = null;
  protected estado: string | null = null;

  constructor() { this.buscar(0); }

  protected buscar(page: number): void {
    this.http.get<Pagina<S['FacturaResumen']>>('/api/v1/ventas/facturas', { params: params({
      numero: this.numero, desde: isoFecha(this.desde), hasta: isoFecha(this.hasta), estado: this.estado, page, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (e) => this.notif.error(e) });
  }

  protected cambiarPagina(e: PageEvent): void { this.buscar(e.pageIndex); }

  protected readonly exportando = signal(false);
  private readonly auth = inject(AuthService);

  /** Todas las facturas del filtro actual (no solo la página visible). */
  protected excel(): void {
    this.exportando.set(true);
    traerTodo<S['FacturaResumen']>(this.http, '/api/v1/ventas/facturas',
      { numero: this.numero, desde: isoFecha(this.desde), hasta: isoFecha(this.hasta), estado: this.estado, sort: 'id,asc' })
      .subscribe({
        next: (fs) => {
          this.exportando.set(false);
          const filtro = [this.desde ? 'Desde ' + fechaRd(isoFecha(this.desde)) : '', this.hasta ? 'hasta ' + fechaRd(isoFecha(this.hasta)) : '',
            this.estado ? 'Estado ' + this.estado : ''].filter(Boolean).join(' · ');
          exportarExcel({
            archivo: 'facturas', titulo: 'Facturas', subtitulos: subtitulosEmpresa(this.auth.perfil()?.compania, filtro),
            columnas: [{ titulo: 'No.', tipo: 'numero' }, { titulo: 'Fecha', tipo: 'fecha' }, { titulo: 'Cliente' }, { titulo: 'NCF' },
              { titulo: 'Condición' }, { titulo: 'Total', tipo: 'moneda' }, { titulo: 'Balance', tipo: 'moneda' }, { titulo: 'Estado' }],
            filas: fs.map((f) => [f.numero, f.fecha, f.clienteNombre, f.encf, f.condicion, f.total, f.balance, f.estado]),
            totales: ['TOTAL', null, `${fs.length} factura(s)`, null, null,
              fs.filter((f) => f.estado !== 'ANULADA').reduce((s, f) => s + (f.total ?? 0), 0),
              fs.filter((f) => f.estado !== 'ANULADA').reduce((s, f) => s + (f.balance ?? 0), 0), null],
          });
        },
        error: (e) => { this.exportando.set(false); this.notif.error(e); },
      });
  }

  protected abrir(f: S['FacturaResumen']): void { this.router.navigate(['/ventas/facturas', f.id]); }
}
