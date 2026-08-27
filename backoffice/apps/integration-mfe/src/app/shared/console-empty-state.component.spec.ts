import { TestBed } from '@angular/core/testing';
import { ConsoleEmptyStateComponent } from './console-empty-state.component';

describe('ConsoleEmptyStateComponent', () => {
  it('renders the provided title and description', async () => {
    await TestBed.configureTestingModule({ imports: [ConsoleEmptyStateComponent] }).compileComponents();
    const fixture = TestBed.createComponent(ConsoleEmptyStateComponent);
    fixture.componentRef.setInput('title', 'Sin datos aún');
    fixture.componentRef.setInput('description', 'Esta vista requiere una API que todavía no existe.');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Sin datos aún');
    expect(fixture.nativeElement.textContent).toContain('Esta vista requiere una API que todavía no existe.');
  });
});
