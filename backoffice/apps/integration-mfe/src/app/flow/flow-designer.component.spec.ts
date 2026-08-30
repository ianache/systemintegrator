import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { FlowDesignerComponent } from './flow-designer.component';

const FLOW_DRAFT = {
  id: 'f-1',
  tenantId: 't-1',
  code: 'flow/vehiculo-alta',
  name: 'Alta de vehiculos',
  draftGraph: { nodes: [] },
  triggerSummary: 'CRON */5',
  activeVersionNumber: null,
  status: 'DRAFT',
  nodeCount: 0,
  archived: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
  version: 0,
};

const FLOW_VERSION = {
  id: 'v-1',
  flowId: 'f-1',
  versionNumber: 1,
  graph: { nodes: [] },
  state: 'ACTIVE',
  publishedBy: 'user@tenant',
  publishedAt: '2026-08-30T00:00:00Z',
};

function setup() {
  TestBed.configureTestingModule({
    imports: [FlowDesignerComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { paramMap: new BehaviorSubject(convertToParamMap({ flowId: 'f-1' })).asObservable() },
      },
    ],
  });
  return {
    http: TestBed.inject(HttpTestingController),
    fixture: TestBed.createComponent(FlowDesignerComponent),
  };
}

describe('FlowDesignerComponent', () => {
  it('loads the flow and its version history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Alta de vehiculos');
    http.verify();
  });

  it('disables the publish button when the draft graph is empty', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, draftGraph: null });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    const publishBtn = fixture.nativeElement.querySelector('.publish-btn') as HTMLButtonElement;
    expect(publishBtn.disabled).toBe(true);
    http.verify();
  });

  it('enables the publish button when the draft graph has content', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    const publishBtn = fixture.nativeElement.querySelector('.publish-btn') as HTMLButtonElement;
    expect(publishBtn.disabled).toBe(false);
    http.verify();
  });

  it('saves draft changes', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.save-draft-btn') as HTMLButtonElement).click();

    const request = http.expectOne('/bff/api/v1/flows/f-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.expectedVersion).toBe(0);
    request.flush({ ...FLOW_DRAFT, version: 1 });
    http.verify();
  });

  it('publishes the draft and reloads the version history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.publish-btn') as HTMLButtonElement).click();

    http.expectOne('/bff/api/v1/flows/f-1/versions/publish').flush(FLOW_VERSION);
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 1 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([FLOW_VERSION]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('PUBLISHED');
    http.verify();
  });

  it('rolls back to a version listed in the history', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 2 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([
      { ...FLOW_VERSION, id: 'v-2', versionNumber: 2, state: 'ACTIVE' },
      { ...FLOW_VERSION, id: 'v-1', versionNumber: 1, state: 'PUBLISHED' },
    ]);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.rollback-btn') as HTMLButtonElement).click();

    const request = http.expectOne('/bff/api/v1/flows/f-1/versions/1/rollback');
    expect(request.request.method).toBe('POST');
    request.flush({ ...FLOW_VERSION, versionNumber: 1, state: 'ACTIVE' });
    http.expectOne('/bff/api/v1/flows/f-1').flush({ ...FLOW_DRAFT, status: 'PUBLISHED', activeVersionNumber: 1 });
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    http.verify();
  });

  it('navigates back to the flows list', () => {
    const { http, fixture } = setup();
    fixture.detectChanges();
    http.expectOne('/bff/api/v1/flows/f-1').flush(FLOW_DRAFT);
    http.expectOne('/bff/api/v1/flows/f-1/versions').flush([]);
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigate');
    (fixture.nativeElement.querySelector('.back-link') as HTMLButtonElement).click();
    expect(navigateSpy).toHaveBeenCalledWith(['/integration/flows']);
    http.verify();
  });
});
