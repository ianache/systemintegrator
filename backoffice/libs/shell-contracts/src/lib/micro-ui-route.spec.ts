import { buildMicroUiRoute } from './micro-ui-route';

describe('buildMicroUiRoute', () => {
  it('builds a lazy Angular route pointing at the remote exposed module', () => {
    const route = buildMicroUiRoute({
      path: 'integration',
      remoteName: 'integrationMfe',
      remoteEntry: 'http://localhost:4202/remoteEntry.json',
      exposedModule: './Routes',
    });

    expect(route.path).toBe('integration');
    expect(typeof route.loadChildren).toBe('function');
  });
});
