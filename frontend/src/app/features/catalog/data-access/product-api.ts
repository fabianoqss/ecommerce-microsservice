import { Service } from '@angular/core';
import { HttpParams, httpResource } from '@angular/common/http';
import { Page } from './page';
import { Product } from './product';
import { ProductFilter } from './product-filter';

@Service()
export class ProductApi {
  list(filter: () => ProductFilter) {
    return httpResource<Page<Product>>(() => {
      const { page, name, category } = filter();
      let params = new HttpParams().set('page', page).set('size', 8);
      if (name.trim()) {
        params = params.set('name', name.trim());
      }
      if (category.trim()) {
        params = params.set('category', category.trim());
      }
      return { url: '/api/product', params };
    });
  }

  categories() {
    return httpResource<string[]>(() => '/api/product/categories');
  }
}
