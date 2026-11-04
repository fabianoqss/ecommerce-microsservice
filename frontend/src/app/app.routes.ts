import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./core/layout/public-layout/public-layout').then((module) => module.PublicLayout),
    children: [
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
    ],
  },
  {
    path: 'admin',
    canMatch: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin.routes').then((module) => module.adminRoutes),
  },
  { path: '**', redirectTo: '' },
];
