import { FormControl } from '@angular/forms';
import { httpsUrlValidator } from './https-url.validator';

describe('httpsUrlValidator', () => {
  it.each([
    '',
    '   ',
    'https://example.com/image.png',
    ' HTTPS://example.com/image.png ',
    '\t https://example.com/image.png \n',
    'https://example.com/image%20name.png',
    'https://example.com/@image.png?email=a@b.com#@image',
    'https://example.com/' + 'a'.repeat(2028),
  ])('accepts the trimmed optional URL %s', (value) => {
    expect(httpsUrlValidator(new FormControl(value, { nonNullable: true }))).toBeNull();
  });

  it.each([
    'http://example.com/image.png',
    'javascript:alert(1)',
    'data:image/png;base64,aGVsbG8=',
    '/images/product.png',
    'https://example.com/image name.png',
    'https://example.com/image\tname.png',
    'https://example.com/image\nname.png',
    'https://example.com/image\u0000name.png',
    'https://example.com/image\u007fname.png',
    'https://example.com/image\u0085name.png',
    'https://user:pass@example.com/image.png',
    'https://user@example.com/image.png',
    'https://:pass@example.com/image.png',
    'https://good.com@evil.com',
    'https://@example.com/image.png',
    'https://:@example.com/image.png',
    'https:////@example.com/image.png',
    'https:\\@example.com/image.png',
    'https://',
    'https://?image.png',
    'https://[invalid]/image.png',
    'https:example.com/image.png',
    'https:/example.com/image.png',
    'https:\\\\example.com\\image.png',
    'https:///example.com/image.png',
    'https://example.com\\image.png',
  ])('rejects invalid URL %s', (value) => {
    expect(httpsUrlValidator(new FormControl(value, { nonNullable: true }))).toEqual({
      validation: 'Enter a valid HTTPS URL',
    });
  });

  it('rejects more than 2048 characters after trimming spaces', () => {
    const value = ' https://example.com/' + 'a'.repeat(2029) + ' ';
    expect(httpsUrlValidator(new FormControl(value, { nonNullable: true }))).toEqual({
      validation: 'The URL must be at most 2048 characters',
    });
  });
});
