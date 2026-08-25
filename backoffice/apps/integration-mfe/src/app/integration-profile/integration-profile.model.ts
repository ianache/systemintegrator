export interface IntegrationProfile {
  id: string;
  businessDomain: string;
  externalSource: string;
  syncDirection: string;
  active: boolean;
  version: number;
}
