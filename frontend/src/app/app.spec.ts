import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { CartStore } from './core/cart/cart-store';
import { LocalStorageCartStore } from './core/cart/local-storage-cart-store';

@Component({ template: '<p>Conteúdo da rota</p>' })
class RouteStub {}

describe('App', () => {
  async function render(): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '', component: RouteStub }]),
        { provide: CartStore, useClass: LocalStorageCartStore },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra o cabeçalho com a marca da loja', async () => {
    const element = await render();

    const header = element.querySelector('header');
    expect(header).not.toBeNull();
    expect(header?.textContent).toContain('DS Catalog');
  });

  it('exibe a página da rota atual dentro da região principal', async () => {
    const element = await render();

    const main = element.querySelector('main');
    expect(main).not.toBeNull();
    expect(main?.textContent).toContain('Conteúdo da rota');
  });
});
