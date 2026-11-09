import { inject, Service } from '@angular/core';
import { HttpClient, HttpParams, httpResource } from '@angular/common/http';
import { Page } from './page';
import { Product } from './product';
import { ProductFilter } from './product-filter';

@Service()
export class ProductApi {
  private readonly http = inject(HttpClient);

  getById(id: string) {
    return this.http.get<Product>('/api/product/' + encodeURIComponent(id));
  }

  list(filter: () => ProductFilter, size = 8) {
    return httpResource<Page<Product>>(() => {
      const { page, name, category } = filter();
      let params = new HttpParams().set('page', page).set('size', size);
      if (name.trim()) {
        params = params.set('name', name.trim());
      }
      if (category.trim()) {
        params = params.set('category', category.trim());
      }
      return { url: '/api/product', params };
    });
  }

  byId(id: () => string) {
    return httpResource<Product>(() => '/api/product/' + encodeURIComponent(id()));
  }

  categories() {
    return httpResource<string[]>(() => '/api/product/categories');
  }
}
