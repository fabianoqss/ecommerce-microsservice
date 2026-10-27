import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Home } from './home';

describe('Home', () => {
  async function render(): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideRouter([])],
    }).compileComponents();

    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra o título principal da página', async () => {
    const element = await render();

    const heading = element.querySelector('h1');
    const accessibleText = heading?.textContent?.replace(/\s+/g, ' ').trim();
    expect(accessibleText).toBe('Conheça o melhor catálogo de produtos');
  });

  it('leva o usuário ao catálogo pelo botão de busca', async () => {
    const element = await render();

    const link = Array.from(element.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('INICIE AGORA A SUA BUSCA'),
    );
    expect(link?.getAttribute('href')).toBe('/catalog');
  });

  it('esconde a ilustração decorativa dos leitores de tela', async () => {
    const element = await render();

    const images = Array.from(element.querySelectorAll('img'));
    expect(images.length).toBeGreaterThan(0);
    for (const image of images) {
      expect(image.getAttribute('alt')).toBe('');
      expect(image.closest('[aria-hidden="true"]')).not.toBeNull();
    }
  });
});
