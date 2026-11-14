import { Component, computed, forwardRef, input, output, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatDatepicker, MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** "2026-11-13" → Date local (sin desfase de zona horaria); vacío o inválido → null. */
export function deIso(v: string | null | undefined): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v ?? '');
  return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
}

/** Date → "2026-11-13" (fecha local). */
export function aIso(d: Date | null | undefined): string | null {
  if (!d || isNaN(d.getTime())) return null;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Campo de fecha con calendario (en todos los navegadores, en español, semana desde el domingo). Se elige en el
 * calendario o se escribe dd/mm/aaaa. El valor es texto ISO "aaaa-mm-dd" (lo que usa la API), con ngModel o
 * formControlName. `compacto` es para celdas de tabla (sin el marco del campo).
 */
@Component({
  selector: 'app-fecha',
  imports: [MatFormFieldModule, MatInputModule, MatDatepickerModule],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => Fecha), multi: true }],
  host: { '[class.compacto]': 'compacto()' },
  template: `
    @if (compacto()) {
      <span class="celda-fecha" (click)="abrir(dpc)">
        <input class="celda fecha" [matDatepicker]="dpc" [value]="fecha()" [min]="minimo()" [max]="maximo()" [required]="requerido()"
               [disabled]="deshabilitado()" placeholder="dd/mm/aaaa" [attr.aria-label]="ariaLabel() || etiqueta()"
               [class.invalida]="invalida()" (dateChange)="cambiar($event.value, $event.targetElement)" (blur)="alTocar()" />
        <mat-datepicker-toggle [for]="dpc" [disabled]="deshabilitado()" />
        <mat-datepicker #dpc />
      </span>
    } @else {
      <mat-form-field (click)="abrir(dp)">
        <mat-label>{{ etiqueta() }}</mat-label>
        <input matInput [matDatepicker]="dp" [value]="fecha()" [min]="minimo()" [max]="maximo()" [required]="requerido()"
               [disabled]="deshabilitado()" placeholder="dd/mm/aaaa" [attr.aria-label]="ariaLabel() || null"
               (dateChange)="cambiar($event.value, $event.targetElement)" (blur)="alTocar()" />
        <mat-datepicker-toggle matIconSuffix [for]="dp" [disabled]="deshabilitado()" />
        <mat-datepicker #dp />
        @if (invalida()) { <mat-hint class="error-texto">Fecha no válida: use dd/mm/aaaa o elíjala en el calendario</mat-hint> }
        @else if (pista()) { <mat-hint>{{ pista() }}</mat-hint> }
      </mat-form-field>
    }`,
  styles: `:host { display: block; flex: 1 1 180px; min-width: 0; }
    :host(.compacto) { display: inline-block; flex: none; }
    mat-form-field { width: 100%; }
    .celda-fecha { display: inline-flex; align-items: center; gap: 2px; }
    .celda-fecha .fecha { width: 110px; }
    .celda-fecha mat-datepicker-toggle { --mat-icon-button-state-layer-size: 32px; }
    .invalida { outline: 2px solid var(--mat-sys-error); }`,
})
export class Fecha implements ControlValueAccessor {
  readonly etiqueta = input('');
  readonly pista = input('');
  readonly ariaLabel = input('');
  /** Límites en ISO "aaaa-mm-dd". */
  readonly min = input<string | null | undefined>(null);
  readonly max = input<string | null | undefined>(null);
  readonly requerido = input(false);
  readonly compacto = input(false);
  /** Después de actualizar el modelo (para recargar listados al cambiar el filtro). */
  readonly cambio = output<string | null>();

  private readonly valor = signal<string | null>(null);
  protected readonly fecha = computed(() => deIso(this.valor()));
  protected readonly minimo = computed(() => deIso(this.min()));
  protected readonly maximo = computed(() => deIso(this.max()));
  protected readonly deshabilitado = signal(false);
  /** Lo escrito no es una fecha (31/02/2026, "mañana"...): el valor queda vacío y se avisa. */
  protected readonly invalida = signal(false);
  private onChange: (v: string | null) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(v: string | null): void { this.valor.set(v || null); this.invalida.set(false); }
  registerOnChange(fn: (v: string | null) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(d: boolean): void { this.deshabilitado.set(d); }

  protected cambiar(d: Date | null, campo?: HTMLElement): void {
    const v = aIso(d);
    this.invalida.set(!v && !!(campo as HTMLInputElement | undefined)?.value?.trim());
    if (v === this.valor()) return;
    this.valor.set(v);
    this.onChange(v);
    this.cambio.emit(v);
  }

  protected alTocar(): void { this.onTouched(); }

  /** Un clic en cualquier parte del campo (texto, etiqueta o borde) abre el calendario. */
  protected abrir(dp: MatDatepicker<Date>): void {
    if (!this.deshabilitado() && !dp.opened) dp.open();
  }
}
