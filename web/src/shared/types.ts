export interface ProxyConfig {
  host: string;
  port: number;
  user?: string;
  pass?: string;
}

export interface IronmanConfig {
  enabled: boolean;
  type: 'STANDARD_IRONMAN' | 'HARDCORE_IRONMAN' | 'ULTIMATE_IRONMAN' | null;
  hcimSafetyLevel: 'NORMAL' | 'CAUTIOUS' | 'PARANOID' | null;
}

export interface ResourceConfig {
  cpuLimit: string;
  memoryLimit: string;
}

export interface BotConfig {
  id: string;
  name: string;
  username: string;  // Jagex account email
  password: string;  // Jagex account password
  totpSecret?: string;  // Optional: Base32 TOTP secret for 2FA
  proxy: ProxyConfig | null;
  ironman: IronmanConfig;
  resources: ResourceConfig;
  vncPort: number;
}

export interface BotStatus {
  id: string;
  containerId: string | null;
  state: 'stopped' | 'running' | 'starting' | 'stopping' | 'error';
  error?: string;
}

export interface BotWithStatus extends BotConfig {
  status: BotStatus;
}

export interface BotsConfigFile {
  bots: BotConfig[];
}

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

