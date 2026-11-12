export function parsePrice(input: string): number | null {
  const value = input.trim();
  if (!/^(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d{1,2})?$/.test(value)) {
    return null;
  }
  const price = Number(value.replaceAll(',', ''));
  return price > 0 && price <= 999999999999.99 ? price : null;
}
