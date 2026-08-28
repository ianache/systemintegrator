import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CredentialService } from './credential.service';

describe('CredentialService', () => {
  it('lists credentials through the BFF', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(CredentialService);
    const http = TestBed.inject(HttpTestingController);

    service.list().subscribe((credentials) => expect(credentials.length).toBe(1));
    const request = http.expectOne('/bff/api/v1/credentials');
    expect(request.request.method).toBe('GET');
    request.flush([{ ref: 'secret/cl2/cred', type: 'BEARER', usedBy: ['units · comsatel'], rotatedAt: null, state: 'VIGENTE' }]);
    http.verify();
  });
});
