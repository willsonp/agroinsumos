import { Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { GENERALES } from './configs';

@Component({
  selector: 'app-generales',
  imports: [MatTabsModule, CatalogoCrud],
  template: `<div class="pagina"><div class="pagina-titulo"><h1>Catálogos generales</h1></div>
    <mat-tab-group class="tarjeta" preserveContent="false">
      @for (g of catalogos; track g.clave) {
        <mat-tab [label]="g.clave"><ng-template matTabContent><app-catalogo-crud [config]="g.config" /></ng-template></mat-tab>
      }
    </mat-tab-group></div>`,
})
export class Generales { protected readonly catalogos = GENERALES; }
