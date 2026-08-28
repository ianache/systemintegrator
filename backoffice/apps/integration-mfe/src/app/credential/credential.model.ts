export interface CredentialSummary {
  ref: string;
  type: string | null;
  usedBy: string[];
  rotatedAt: string | null;
  state: 'VIGENTE' | 'SIN_VERIFICAR';
}
