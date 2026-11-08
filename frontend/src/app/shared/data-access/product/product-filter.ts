export interface ProductFilter {
  page: number;
  name: string;
  category: string;
}

export function parseProductFilter(params: {
  page?: string | string[];
  name?: string | string[];
  category?: string | string[];
}): ProductFilter {
  const rawPage = params.page ?? '1';
  const page = typeof rawPage === 'string' && /^\d+$/.test(rawPage) ? Number(rawPage) : 1;
  return {
    page: Number.isSafeInteger(page) && page > 0 && page <= 2147483647 ? page - 1 : 0,
    name: typeof params.name === 'string' ? params.name.trim().slice(0, 100) : '',
    category: typeof params.category === 'string' ? params.category.trim() : '',
  };
}
