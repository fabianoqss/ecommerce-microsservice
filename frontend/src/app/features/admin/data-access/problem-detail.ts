import { HttpErrorResponse } from '@angular/common/http';

export function problemDetail(error: unknown, fallback: string): string {
  const problem: unknown = error instanceof HttpErrorResponse ? error.error : undefined;
  return problem &&
    typeof problem === 'object' &&
    'detail' in problem &&
    typeof problem.detail === 'string' &&
    problem.detail.trim()
    ? problem.detail
    : fallback;
}

export function problemFields(error: unknown): Record<string, unknown> {
  const problem: unknown = error instanceof HttpErrorResponse ? error.error : undefined;
  if (
    problem &&
    typeof problem === 'object' &&
    'fields' in problem &&
    problem.fields &&
    typeof problem.fields === 'object' &&
    !Array.isArray(problem.fields)
  ) {
    return Object.fromEntries(Object.entries(problem.fields));
  }
  return {};
}
