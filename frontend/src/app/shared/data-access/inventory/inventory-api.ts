import { computed, Service } from '@angular/core';
import { HttpParams, httpResource } from '@angular/common/http';
import { InventoryItem } from './inventory';

const sameCodes = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && a.every((code, index) => code === b[index]);

@Service()
export class InventoryApi {
  bySkus(skus: () => string[]) {
    const codes = computed(() => skus(), { equal: sameCodes });
    return httpResource<InventoryItem[]>(() => {
      const current = codes();
      if (current.length === 0) return undefined;
      const params = new HttpParams({ fromObject: { skuCode: current } });
      return { url: '/api/inventory', params };
    });
  }
}
