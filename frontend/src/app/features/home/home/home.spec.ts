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

  it('shows the main page title', async () => {
    const element = await render();

    const heading = element.querySelector('h1');
    const accessibleText = heading?.textContent?.replace(/\s+/g, ' ').trim();
    expect(accessibleText).toBe('Explore our product catalog');
  });

  it('takes the user to the catalog through the search button', async () => {
    const element = await render();

    const link = Array.from(element.querySelectorAll('a')).find((a) =>
      a.textContent?.includes('START SHOPPING'),
    );
    expect(link?.getAttribute('href')).toBe('/catalog');
  });

  it('hides the decorative illustration from screen readers', async () => {
    const element = await render();

    const images = Array.from(element.querySelectorAll('img'));
    expect(images.length).toBeGreaterThan(0);
    for (const image of images) {
      expect(image.getAttribute('alt')).toBe('');
      expect(image.closest('[aria-hidden="true"]')).not.toBeNull();
    }
  });
});
