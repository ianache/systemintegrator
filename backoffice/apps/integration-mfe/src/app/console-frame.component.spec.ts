import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ConsoleFrameComponent } from './console-frame.component';
import { ToastService } from './shared/toast.service';

describe('ConsoleFrameComponent', () => {
  it('renders the router outlet and shows a toast when ToastService has a message', async () => {
    await TestBed.configureTestingModule({
      imports: [ConsoleFrameComponent],
      providers: [provideRouter([])],
    }).compileComponents();

    const fixture = TestBed.createComponent(ConsoleFrameComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.toast')).toBeNull();

    TestBed.inject(ToastService).show('Perfil creado');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.toast')?.textContent).toContain('Perfil creado');
  });
});
