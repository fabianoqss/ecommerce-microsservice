import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'DS Catalog',
    loadComponent: () => import('./features/home/home/home').then((m) => m.Home),
  },
  {
    path: 'catalog',
    title: 'Catálogo | DS Catalog',
    loadChildren: () =>
      import('./features/catalog/catalog.routes').then((module) => module.catalogRoutes),
  },
  { path: '**', redirectTo: '' },
];
