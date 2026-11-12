import { ESCANEADOS_EMPAQUE } from './calculo';
import { Component, ElementRef, inject, output, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { catchError, debounceTime, distinctUntilChanged, filter, map, of, switchMap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';
import { Notificacion } from '../core/notificacion.service';

type Articulo = S['ArticuloResponse'];

/**
 * Buscador de artículos. Con lector de código de barras: el lector "escribe" el código y envía
 * Enter, que busca por código exacto. Escribiendo letras, autocompleta por nombre/referencia.
 */
@Component({
  selector: 'app-articulo-buscador',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, CurrencyPipe, DecimalPipe],
  template: `
    <mat-form-field style="width:100%">
      <mat-label>Artículo</mat-label>
      <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">barcode_scanner</span>
      <input #campo matInput [formControl]="texto" [matAutocomplete]="auto" (keydown.enter)="porCodigo($event)"
             placeholder="Escanee el código o escriba el nombre" />
      <mat-autocomplete #auto (optionSelected)="elegir($event.option.value)">
        @for (a of opciones(); track a.id) {
          <mat-option [value]="a">
            <div style="display:flex;gap:12px;justify-content:space-between;width:100%">
              <span>{{ a.nombre }} <span class="muted">{{ a.codigoBarra }}</span></span>
              <span class="muted">{{ a.precioContadoConItbis | currency }} · Exist. {{ a.existencia | number: '1.0-2' }}</span>
            </div>
          </mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>`,
})
export class ArticuloBuscador {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  readonly elegido = output<Articulo>();
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  protected readonly texto = new FormControl<string | Articulo>('');

  protected readonly opciones = toSignal(this.texto.valueChanges.pipe(
    filter((v) => typeof v === 'string'),
    map((v) => (v as string).trim()),
    debounceTime(250),
    distinctUntilChanged(),
    switchMap((q) => (q.length < 2 || /^\d{6,}$/.test(q)) ? of([]) :
      this.http.get<Pagina<Articulo>>('/api/v1/inventario/articulos', { params: params({ q, size: 15, sort: 'nombre' }) })
        .pipe(map((p) => p.content.filter((a) => a.estado === 'A')), catchError(() => of([])))),
  ), { initialValue: [] as Articulo[] });

  protected porCodigo(ev: Event): void {
    const v = this.texto.value;
    if (typeof v !== 'string' || !/^\w{3,}$/.test(v.trim()) || this.opciones().length) return;
    ev.preventDefault();
    this.http.get<Articulo>(`/api/v1/inventario/articulos/codigo/${encodeURIComponent(v.trim())}`).subscribe({
      next: (a) => {
        // Código de la caja/saco: la línea entra como presentación
        if (a.codigoBarraEmpaque && a.codigoBarraEmpaque === v.trim()) ESCANEADOS_EMPAQUE.add(a);
        this.elegir(a);
      },
      error: () => this.notif.error(null, `No existe un artículo con el código ${v}`),
    });
  }

  protected elegir(a: Articulo): void {
    this.elegido.emit(a);
    this.texto.setValue('');
    this.enfocar();
  }

  enfocar(): void {
    setTimeout(() => this.campo().nativeElement.focus());
  }
}
