import { ForbiddenException, HttpException, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios from 'axios';
import { GatewayProxyService } from './gateway-proxy.service';

describe('GatewayProxyService', () => {
  let service: GatewayProxyService;
  const config = { getOrThrow: () => 'http://gateway.internal' } as unknown as ConfigService;

  beforeEach(() => {
    service = new GatewayProxyService(config);
  });

  afterEach(() => jest.restoreAllMocks());

  it('lists profiles with the activeOnly filter forwarded as a query param', async () => {
    const get = jest.spyOn(axios, 'get').mockResolvedValue({ data: [{ id: 'p-1' }] });
    const result = await service.getIntegrationProfiles('token-1', false);
    expect(result).toEqual([{ id: 'p-1' }]);
    expect(get).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles?activeOnly=false', {
      headers: { Authorization: 'Bearer token-1' },
    });
  });

  it('gets a single profile by id', async () => {
    jest.spyOn(axios, 'get').mockResolvedValue({ data: { id: 'p-1' } });
    const result = await service.getIntegrationProfile('token-1', 'p-1');
    expect(result).toEqual({ id: 'p-1' });
  });

  it('creates a profile, forwarding only the bearer token and the body', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { id: 'p-1' } });
    await service.createIntegrationProfile('token-1', { businessDomain: 'vehicle' });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles',
      { businessDomain: 'vehicle' },
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('updates a profile', async () => {
    const put = jest.spyOn(axios, 'put').mockResolvedValue({ data: { id: 'p-1', version: 8 } });
    const result = await service.updateIntegrationProfile('token-1', 'p-1', { expectedVersion: 7 });
    expect(result).toEqual({ id: 'p-1', version: 8 });
    expect(put).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles/p-1',
      { expectedVersion: 7 },
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('deactivates a profile', async () => {
    const del = jest.spyOn(axios, 'delete').mockResolvedValue({ data: undefined });
    await service.deactivateIntegrationProfile('token-1', 'p-1');
    expect(del).toHaveBeenCalledWith('http://gateway.internal/api/v1/integration-profiles/p-1', {
      headers: { Authorization: 'Bearer token-1' },
    });
  });

  it('triggers a sync', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { status: 'TRIGGERED' } });
    const result = await service.triggerSync('token-1', 'p-1');
    expect(result).toEqual({ status: 'TRIGGERED' });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/integration-profiles/p-1/sync',
      {},
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('replays the dead letter queue', async () => {
    const post = jest.spyOn(axios, 'post').mockResolvedValue({ data: { total: 3, success: 2, failed: 1 } });
    const result = await service.replayDeadLetterQueue('token-1');
    expect(result).toEqual({ total: 3, success: 2, failed: 1 });
    expect(post).toHaveBeenCalledWith(
      'http://gateway.internal/api/v1/inbox/dlq/replay',
      {},
      { headers: { Authorization: 'Bearer token-1' } },
    );
  });

  it('maps a 401 to UnauthorizedException regardless of the failing call', async () => {
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 401 } });
    await expect(service.createIntegrationProfile('token-1', {})).rejects.toBeInstanceOf(UnauthorizedException);
  });

  it('maps a 403 to ForbiddenException', async () => {
    jest.spyOn(axios, 'delete').mockRejectedValue({ response: { status: 403 } });
    await expect(service.deactivateIntegrationProfile('token-1', 'p-1')).rejects.toBeInstanceOf(ForbiddenException);
  });

  it('passes through a 409 conflict body and status from the Gateway', async () => {
    const problem = { title: 'Conflict', status: 409, detail: 'An active integration profile already exists', errorCode: 'INTEGRATION_PROFILE_CONFLICT' };
    jest.spyOn(axios, 'post').mockRejectedValue({ response: { status: 409, data: problem } });

    await expect(service.createIntegrationProfile('token-1', {})).rejects.toMatchObject({
      status: 409,
      response: problem,
    });
  });

  it('passes through a 404 not-found body', async () => {
    const problem = { title: 'Not Found', status: 404, errorCode: 'INTEGRATION_PROFILE_NOT_FOUND' };
    jest.spyOn(axios, 'get').mockRejectedValue({ response: { status: 404, data: problem } });

    const error = (await service.getIntegrationProfile('token-1', 'missing').catch((e) => e)) as HttpException;
    expect(error).toBeInstanceOf(HttpException);
    expect(error.getStatus()).toBe(404);
    expect(error.getResponse()).toEqual(problem);
  });

  it('maps a network failure with no response to BadGatewayException', async () => {
    jest.spyOn(axios, 'get').mockRejectedValue(new Error('ECONNREFUSED'));
    await expect(service.getIntegrationProfiles('token-1', true)).rejects.toThrow('Gateway is unavailable');
  });
});
