const formatter = new Intl.NumberFormat('pt-BR', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function priceParts(price: number): { integer: string; decimal: string } {
  const parts = formatter.formatToParts(price);
  return {
    integer: parts
      .filter((part) => ['integer', 'group', 'minusSign'].includes(part.type))
      .map((part) => part.value)
      .join(''),
    decimal: parts
      .filter((part) => ['decimal', 'fraction'].includes(part.type))
      .map((part) => part.value)
      .join(''),
  };
}
