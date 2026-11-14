import { Component, ElementRef, inject, signal, viewChildren } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap, tap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';

type Articulo = S['ArticuloResponse'];

/**
 * Ventana de búsqueda de artículos (legacy "Lista de Artículos"): un filtro por nombre, código o referencia y la lista
 * con existencia y precio. Clic, o flechas + Enter, elige el artículo y vuelve a la pantalla que la abrió.
 */
@Component({
  selector: 'app-articulo-busqueda-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatProgressBarModule, CurrencyPipe, DecimalPipe],
  template: `
    <h2 mat-dialog-title>Buscar artículo</h2>
    <mat-dialog-content>
      <mat-form-field class="filtro">
        <mat-label>Escriba parte del nombre, el código o la referencia</mat-label>
        <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">search</span>
        <input matInput [formControl]="texto" (keydown)="teclado($event)" cdkFocusInitial autocomplete="off" />
      </mat-form-field>
      @if (cargando()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="lista" role="listbox" aria-label="Artículos">
        <table>
          <thead><tr><th>Código</th><th>Nombre</th><th class="num">Existencia</th><th class="num">Precio</th></tr></thead>
          <tbody>
            @for (a of articulos(); track a.id; let i = $index) {
              <tr #fila role="option" [attr.aria-selected]="i === activo()" [class.activo]="i === activo()" [class.agotado]="a.tipo === 'B' && (a.existencia ?? 0) <= 0"
                  (click)="elegir(a)" (mouseenter)="activo.set(i)">
                <td>{{ a.codigoBarra || a.referencia || a.id }}</td>
                <td>{{ a.nombre }}@if (a.unidadEmpaque) { <span class="muted"> · {{ a.unidadEmpaque }} x {{ a.factorEmpaque }}</span> }</td>
                <td class="num">{{ a.tipo === 'S' ? 'Servicio' : (a.existencia | number: '1.0-2') }}@if (a.tipo !== 'S' && a.unidad) { <span class="muted"> {{ a.unidad }}</span> }</td>
                <td class="num">{{ a.precioContadoConItbis | currency }}</td>
              </tr>
            } @empty {
              @if (!cargando()) { <tr><td colspan="4" class="muted vacio">No hay artículos que coincidan con «{{ texto.value }}»</td></tr> }
            }
          </tbody>
        </table>
      </div>
      <p class="muted pequeno">{{ articulos().length }} artículo(s){{ articulos().length >= limite ? ' (se muestran los primeros ' + limite + '; afine la búsqueda)' : '' }}
        · ↑ ↓ para moverse, Enter o clic para elegir, Esc para cerrar</p>
    </mat-dialog-content>`,
  styles: `.filtro { width: 100%; }
    .lista { height: min(440px, 55vh); overflow: auto; border: 1px solid var(--mat-sys-outline-variant); border-radius: 8px; }
    table { width: 100%; border-collapse: collapse; }
    th { position: sticky; top: 0; background: var(--mat-sys-surface-container-high); text-align: left; padding: 8px; font: var(--mat-sys-label-large); }
    td { padding: 7px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); }
    th.num, td.num { text-align: right; white-space: nowrap; }
    tbody tr { cursor: pointer; }
    tr.activo td { background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    tr.agotado td:nth-child(3) { color: var(--mat-sys-error); }
    .vacio { padding: 24px; text-align: center; cursor: default; }`,
})
export class ArticuloBusquedaDialog {
  private readonly http = inject(HttpClient);
  private readonly ref = inject(MatDialogRef<ArticuloBusquedaDialog, Articulo>);
  private readonly filas = viewChildren<ElementRef<HTMLTableRowElement>>('fila');
  protected readonly limite = 100;
  protected readonly texto = new FormControl(inject<{ texto?: string } | null>(MAT_DIALOG_DATA)?.texto ?? '', { nonNullable: true });
  protected readonly activo = signal(0);
  protected readonly cargando = signal(true);

  protected readonly articulos = toSignal(this.texto.valueChanges.pipe(
    startWith(this.texto.value),
    map((v) => v.trim()),
    debounceTime(200),
    distinctUntilChanged(),
    tap(() => this.cargando.set(true)),
    switchMap((q) => this.http.get<Pagina<Articulo>>('/api/v1/inventario/articulos', { params: params({ q, size: this.limite, sort: 'nombre' }) })
      .pipe(map((p) => p.content.filter((a) => a.estado === 'A')), catchError(() => of([] as Articulo[])))),
    tap(() => { this.cargando.set(false); this.activo.set(0); }),
  ), { initialValue: [] as Articulo[] });

  protected teclado(ev: KeyboardEvent): void {
    const n = this.articulos().length;
    if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
      ev.preventDefault();
      if (!n) return;
      this.activo.set((this.activo() + (ev.key === 'ArrowDown' ? 1 : n - 1)) % n);
      this.filas()[this.activo()]?.nativeElement.scrollIntoView({ block: 'nearest' });
    } else if (ev.key === 'PageDown' || ev.key === 'PageUp') {
      ev.preventDefault();
      if (!n) return;
      this.activo.set(Math.max(0, Math.min(n - 1, this.activo() + (ev.key === 'PageDown' ? 10 : -10))));
      this.filas()[this.activo()]?.nativeElement.scrollIntoView({ block: 'nearest' });
    } else if (ev.key === 'Enter') {
      ev.preventDefault();
      const a = this.articulos()[this.activo()];
      if (a) this.elegir(a);
    }
  }

  protected elegir(a: Articulo): void { this.ref.close(a); }
}
