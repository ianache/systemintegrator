export type MessageDirection = 'INBOUND' | 'OUTBOUND';
export type MessageStatus = 'PENDING' | 'ERROR' | 'DLQ' | 'PROCESSED';
export type MessageStatusFilter = 'ALL' | 'PENDING' | 'ERROR' | 'DLQ';

export interface MessageSummary {
  id: string;
  direction: MessageDirection;
  eventType: string;
  domain: string;
  status: MessageStatus;
  attempts: number;
  lastError: string | null;
  timestamp: string;
}

export interface MessageDetail extends MessageSummary {
  payload: string;
}
