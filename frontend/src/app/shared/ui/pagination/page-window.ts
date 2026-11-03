export function pageWindow(
  page: number,
  totalPages: number,
  maximum: number,
): (number | 'ellipsis-start' | 'ellipsis-end')[] {
  if (totalPages <= maximum) {
    return Array.from({ length: totalPages }, (_, index) => index);
  }
  const middleCount = maximum - 2;
  const start = Math.max(
    1,
    Math.min(page - Math.floor(middleCount / 2), totalPages - 1 - middleCount),
  );
  const middle = Array.from({ length: middleCount }, (_, index) => start + index);
  return [
    0,
    ...(start > 1 ? ['ellipsis-start' as const] : []),
    ...middle,
    ...(start + middleCount < totalPages - 1 ? ['ellipsis-end' as const] : []),
    totalPages - 1,
  ];
}
