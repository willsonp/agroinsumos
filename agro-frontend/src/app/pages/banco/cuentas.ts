import { Component } from '@angular/core';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { BANCOS, CUENTAS_BANCARIAS } from './configs';

@Component({
  selector: 'app-cuentas-bancarias',
  imports: [CatalogoCrud],
  template: `<div class="pagina"><div class="pagina-titulo"><h1>Cuentas bancarias</h1></div>
    <div class="tarjeta"><app-catalogo-crud [config]="cuentas" /></div>
    <h2>Bancos</h2>
    <div class="tarjeta"><app-catalogo-crud [config]="bancos" /></div></div>`,
})
export class Cuentas {
  protected readonly cuentas = CUENTAS_BANCARIAS;
  protected readonly bancos = BANCOS;
}
