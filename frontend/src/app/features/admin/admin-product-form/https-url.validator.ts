import { ValidatorFn } from '@angular/forms';

export const httpsUrlValidator: ValidatorFn = (control) => {
  const input: unknown = control.value;
  const invalid = { validation: 'Informe uma URL https válida' };
  if (typeof input !== 'string') return invalid;
  const value = input.trim();
  if (!value) return null;
  if (value.length > 2048) {
    return { validation: 'A URL deve ter no máximo 2048 caracteres' };
  }
  if (!/^https:\/\/[^/\\]/i.test(value)) return invalid;
  if (/[\s\p{Cc}\\]/u.test(value) || /^https:\/\/[^/?#]*@/i.test(value)) return invalid;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname && !url.username && !url.password
      ? null
      : invalid;
  } catch {
    return invalid;
  }
};
