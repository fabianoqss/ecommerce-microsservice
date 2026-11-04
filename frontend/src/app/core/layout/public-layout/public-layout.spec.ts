import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { PublicLayout } from './public-layout';

@Component({ template: '<h1>Página pública</h1>' })
class PageStub {}

describe('PublicLayout', () => {
  it('renderiza a página pública dentro do contêiner', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: '',
            component: PublicLayout,
            children: [{ path: '', component: PageStub }],
          },
        ]),
      ],
    });
    const harness = await RouterTestingHarness.create('/');
    const container = harness.routeNativeElement?.querySelector('div');

    expect(container).toBeTruthy();
    expect(container?.querySelector('router-outlet')).toBeTruthy();
    expect(container?.querySelector('h1')?.textContent).toBe('Página pública');
  });
});
