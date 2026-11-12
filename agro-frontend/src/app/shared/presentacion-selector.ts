import { Component, input, output } from '@angular/core';
import type { S } from '../api/tipos';
import { tieneEmpaque } from './calculo';

/**
 * Selector de unidad de una línea: la unidad base (Libra, Unidad) o la presentación (Saco x 100, Caja x 12).
 * Si el artículo no tiene presentación muestra solo el nombre de la unidad.
 */
@Component({
  selector: 'app-presentacion',
  template: `
    @if (conEmpaque()) {
      <select class="selector" [value]="empaque() ? '1' : '0'" (change)="cambio.emit($any($event.target).value === '1')"
              [attr.aria-label]="'Unidad de ' + articulo().nombre">
        <option value="0">{{ articulo().unidad || 'Unidad' }}</option>
        <option value="1">{{ articulo().unidadEmpaque }} x {{ articulo().factorEmpaque }}</option>
      </select>
    } @else { <span class="muted">{{ articulo().unidad }}</span> }`,
  styles: `.selector { padding: 5px 4px; border-radius: 6px; border: 1px solid var(--mat-sys-outline-variant);
    background: var(--mat-sys-surface); color: var(--mat-sys-on-surface); font: inherit; max-width: 130px; }`,
})
export class PresentacionSelector {
  readonly articulo = input.required<S['ArticuloResponse']>();
  readonly empaque = input(false);
  readonly cambio = output<boolean>();
  protected conEmpaque(): boolean { return tieneEmpaque(this.articulo()); }
}
