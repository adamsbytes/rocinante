/**
 * TypeScript declarations for noVNC RFB client
 * Based on noVNC 1.6.0
 * @see https://github.com/novnc/noVNC/blob/master/docs/API.md
 */

export interface RFBCredentials {
  username?: string;
  password?: string;
  target?: string;
}

export interface RFBOptions {
  shared?: boolean;
  credentials?: RFBCredentials;
  repeaterID?: string;
  wsProtocols?: string[];
}

export interface RFBCapabilities {
  power: boolean;
}

declare class RFB extends EventTarget {
  constructor(target: HTMLElement, urlOrChannel: string | WebSocket, options?: RFBOptions);

  // Properties
  viewOnly: boolean;
  focusOnClick: boolean;
  clipViewport: boolean;
  dragViewport: boolean;
  scaleViewport: boolean;
  resizeSession: boolean;
  showDotCursor: boolean;
  background: string;
  qualityLevel: number;
  compressionLevel: number;

  // Read-only properties
  readonly capabilities: RFBCapabilities;

  // Methods
  disconnect(): void;
  sendCredentials(credentials: RFBCredentials): void;
  sendKey(keysym: number, code: string | null, down?: boolean): void;
  sendCtrlAltDel(): void;
  focus(options?: FocusOptions): void;
  blur(): void;
  machineShutdown(): void;
  machineReboot(): void;
  machineReset(): void;
  clipboardPasteFrom(text: string): void;
  getImageData(): ImageData;
  toDataURL(type?: string, encoderOptions?: number): string;
  toBlob(callback: BlobCallback, type?: string, quality?: number): void;

  // Events (via addEventListener)
  addEventListener(type: 'connect', listener: (e: CustomEvent<{}>) => void): void;
  addEventListener(type: 'disconnect', listener: (e: CustomEvent<{ clean: boolean }>) => void): void;
  addEventListener(type: 'credentialsrequired', listener: (e: CustomEvent<{ types: string[] }>) => void): void;
  addEventListener(type: 'securityfailure', listener: (e: CustomEvent<{ status: number; reason?: string }>) => void): void;
  addEventListener(type: 'clipboard', listener: (e: CustomEvent<{ text: string }>) => void): void;
  addEventListener(type: 'bell', listener: (e: CustomEvent<{}>) => void): void;
  addEventListener(type: 'desktopname', listener: (e: CustomEvent<{ name: string }>) => void): void;
  addEventListener(type: 'capabilities', listener: (e: CustomEvent<{ capabilities: RFBCapabilities }>) => void): void;
  addEventListener(type: string, listener: EventListener): void;
}

export default RFB;
