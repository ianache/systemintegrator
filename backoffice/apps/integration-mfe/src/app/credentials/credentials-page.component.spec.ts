import { TestBed } from '@angular/core/testing';
import { CredentialsPageComponent } from './credentials-page.component';

describe('CredentialsPageComponent', () => {
  it('renders the heading and the no-backend-yet explanation', () => {
    TestBed.configureTestingModule({ imports: [CredentialsPageComponent] });
    const fixture = TestBed.createComponent(CredentialsPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Credenciales');
    expect(fixture.nativeElement.textContent).toContain('no expone');
  });
});
