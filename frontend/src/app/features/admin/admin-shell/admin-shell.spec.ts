import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AdminShell } from './admin-shell';

@Component({ template: '<p>Administrative content</p>' })
class PageStub {}

describe('AdminShell', () => {
  it('shows the menu and renders the child page in the outlet', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'admin',
            component: AdminShell,
            children: [{ path: 'products', component: PageStub }],
          },
        ]),
      ],
    });
    const harness = await RouterTestingHarness.create('/admin/products');
    const element = harness.routeNativeElement;

    expect(element?.querySelector('nav[aria-label="Administration"]')).not.toBeNull();
    expect(element?.querySelector('router-outlet')).not.toBeNull();
    expect(element?.textContent).toContain('Administrative content');
  });
});
