import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { IntegrationTabsComponent } from './integration-tabs.component';

describe('IntegrationTabsComponent', () => {
  it('renders navigation tabs', async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationTabsComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Integration Profiles');
    expect(fixture.nativeElement.textContent).toContain('Flows');
  });
});
