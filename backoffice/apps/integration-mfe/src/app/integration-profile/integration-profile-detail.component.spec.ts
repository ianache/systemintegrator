import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { IntegrationProfileDetailComponent } from './integration-profile-detail.component';

describe('IntegrationProfileDetailComponent', () => {
  it('renders a heading placeholder while the route is wired up', async () => {
    await TestBed.configureTestingModule({
      imports: [IntegrationProfileDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { paramMap: of({ get: () => 'p-1' }) } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(IntegrationProfileDetailComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('p-1');
  });
});
