import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { IntegrationTabsComponent } from './integration-tabs.component';

describe('IntegrationTabsComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationTabsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders navigation tabs', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Integration Profiles');
    expect(fixture.nativeElement.textContent).toContain('Flows');
  });

  it('shows the flow count badge', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush([{ id: 'f-1' }, { id: 'f-2' }]);
    fixture.detectChanges();

    const badge = fixture.nativeElement.querySelector('.tab-badge');
    expect(badge.textContent.trim()).toBe('2');
  });

  it('hides the badge if the flow count request fails', () => {
    const fixture = TestBed.createComponent(IntegrationTabsComponent);
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows').flush('error', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tab-badge')).toBeFalsy();
  });
});
