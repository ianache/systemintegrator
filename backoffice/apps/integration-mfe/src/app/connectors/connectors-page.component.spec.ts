import { TestBed } from '@angular/core/testing';
import { ConnectorsPageComponent } from './connectors-page.component';

describe('ConnectorsPageComponent', () => {
  it('renders the heading and the no-backend-yet explanation', () => {
    TestBed.configureTestingModule({ imports: [ConnectorsPageComponent] });
    const fixture = TestBed.createComponent(ConnectorsPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Conectores y adapters');
    expect(fixture.nativeElement.textContent).toContain('Catálogo de connectors');
  });
});
