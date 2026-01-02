/**
 * VNC Authentication Helper
 * 
 * Implements VNC Auth (RFB security type 2) for authenticating with x11vnc.
 * This allows the web server to authenticate with x11vnc on behalf of the browser,
 * so the browser doesn't need to know the VNC password.
 */

import { createCipheriv } from 'crypto';

/**
 * Reverse bits in a byte (VNC auth quirk).
 * VNC auth requires each byte of the password to have its bits reversed
 * before using it as a DES key.
 */
function reverseBits(byte: number): number {
  let result = 0;
  for (let i = 0; i < 8; i++) {
    result |= ((byte >> i) & 1) << (7 - i);
  }
  return result;
}

/**
 * Prepare VNC password for DES encryption.
 * - Truncate or pad to exactly 8 bytes
 * - Reverse bits in each byte
 */
function prepareVncKey(password: string): Buffer {
  const key = Buffer.alloc(8, 0);
  const passwordBytes = Buffer.from(password, 'utf-8');
  
  // Copy up to 8 bytes from password
  for (let i = 0; i < Math.min(8, passwordBytes.length); i++) {
    key[i] = reverseBits(passwordBytes[i]);
  }
  
  return key;
}

/**
 * Encrypt VNC challenge using DES with the VNC password.
 * VNC uses DES-ECB with a special key preparation.
 * 
 * @param challenge - 16-byte challenge from VNC server
 * @param password - VNC password (max 8 chars used)
 * @returns 16-byte encrypted response
 */
export function encryptVncChallenge(challenge: Buffer, password: string): Buffer {
  if (challenge.length !== 16) {
    throw new Error('VNC challenge must be 16 bytes');
  }
  
  const key = prepareVncKey(password);
  const response = Buffer.alloc(16);
  
  // Encrypt two 8-byte blocks (ECB mode)
  const cipher1 = createCipheriv('des-ecb', key, null);
  cipher1.setAutoPadding(false);
  const block1 = cipher1.update(challenge.subarray(0, 8));
  block1.copy(response, 0);
  
  const cipher2 = createCipheriv('des-ecb', key, null);
  cipher2.setAutoPadding(false);
  const block2 = cipher2.update(challenge.subarray(8, 16));
  block2.copy(response, 8);
  
  return response;
}

/**
 * RFB Handshake state machine for VNC authentication.
 */
export enum RfbState {
  /** Waiting for server version string */
  WAIT_VERSION = 'WAIT_VERSION',
  /** Waiting for security types */
  WAIT_SECURITY_TYPES = 'WAIT_SECURITY_TYPES',
  /** Waiting for VNC auth challenge */
  WAIT_CHALLENGE = 'WAIT_CHALLENGE',
  /** Waiting for auth result */
  WAIT_AUTH_RESULT = 'WAIT_AUTH_RESULT',
  /** Handshake complete, proxying */
  AUTHENTICATED = 'AUTHENTICATED',
  /** Authentication failed */
  FAILED = 'FAILED',
}

/**
 * VNC Handshake Handler
 * 
 * Manages the RFB handshake state machine, authenticating with the VNC server
 * using the provided password. Once authenticated, returns buffered data for
 * forwarding to the browser.
 */
export class VncHandshake {
  private state: RfbState = RfbState.WAIT_VERSION;
  private buffer: Buffer = Buffer.alloc(0);
  private password: string;
  private serverVersion: string = '';
  
  /** Data to send to VNC server */
  private toServer: Buffer[] = [];
  /** Data to forward to browser after auth */
  private toBrowser: Buffer[] = [];
  
  constructor(password: string) {
    this.password = password;
  }
  
  getState(): RfbState {
    return this.state;
  }
  
  /**
   * Check if handshake is complete (authenticated or failed).
   */
  isComplete(): boolean {
    return this.state === RfbState.AUTHENTICATED || this.state === RfbState.FAILED;
  }
  
  /**
   * Check if authentication succeeded.
   */
  isAuthenticated(): boolean {
    return this.state === RfbState.AUTHENTICATED;
  }
  
  /**
   * Get data to send to VNC server.
   */
  getServerData(): Buffer | null {
    if (this.toServer.length === 0) return null;
    const data = Buffer.concat(this.toServer);
    this.toServer = [];
    return data;
  }
  
  /**
   * Get data to forward to browser (only after authentication).
   * This includes the server version and security result that the browser expects.
   */
  getBrowserData(): Buffer | null {
    if (this.toBrowser.length === 0) return null;
    const data = Buffer.concat(this.toBrowser);
    this.toBrowser = [];
    return data;
  }
  
  /**
   * Process data received from VNC server.
   * Returns true if more data is needed, false if handshake is complete.
   */
  processServerData(data: Buffer): boolean {
    this.buffer = Buffer.concat([this.buffer, data]);
    
    while (this.buffer.length > 0 && !this.isComplete()) {
      const consumed = this.processBuffer();
      if (consumed === 0) {
        // Need more data
        break;
      }
      this.buffer = this.buffer.subarray(consumed);
    }
    
    return !this.isComplete();
  }
  
  private processBuffer(): number {
    switch (this.state) {
      case RfbState.WAIT_VERSION:
        return this.handleVersion();
      case RfbState.WAIT_SECURITY_TYPES:
        return this.handleSecurityTypes();
      case RfbState.WAIT_CHALLENGE:
        return this.handleChallenge();
      case RfbState.WAIT_AUTH_RESULT:
        return this.handleAuthResult();
      default:
        return 0;
    }
  }
  
  private handleVersion(): number {
    // Server version is 12 bytes: "RFB xxx.yyy\n"
    if (this.buffer.length < 12) return 0;
    
    this.serverVersion = this.buffer.subarray(0, 12).toString('ascii');
    console.log(`[VNC Auth] Server version: ${this.serverVersion.trim()}`);
    
    // Respond with same version (or 003.008 for best compatibility)
    const clientVersion = 'RFB 003.008\n';
    this.toServer.push(Buffer.from(clientVersion, 'ascii'));
    
    // Forward server version to browser (browser will also send its version)
    this.toBrowser.push(this.buffer.subarray(0, 12));
    
    this.state = RfbState.WAIT_SECURITY_TYPES;
    return 12;
  }
  
  private handleSecurityTypes(): number {
    // First byte is number of security types
    if (this.buffer.length < 1) return 0;
    
    const numTypes = this.buffer[0];
    
    if (numTypes === 0) {
      // Server is rejecting us - read reason string
      if (this.buffer.length < 5) return 0; // Need at least length field
      const reasonLen = this.buffer.readUInt32BE(1);
      if (this.buffer.length < 5 + reasonLen) return 0;
      const reason = this.buffer.subarray(5, 5 + reasonLen).toString('utf-8');
      console.error(`[VNC Auth] Server rejected connection: ${reason}`);
      this.state = RfbState.FAILED;
      return 5 + reasonLen;
    }
    
    if (this.buffer.length < 1 + numTypes) return 0;
    
    const types = Array.from(this.buffer.subarray(1, 1 + numTypes));
    console.log(`[VNC Auth] Security types: ${types.join(', ')}`);
    
    // Look for VNC Auth (type 2)
    if (!types.includes(2)) {
      // Check for None (type 1) as fallback (shouldn't happen with password set)
      if (types.includes(1)) {
        console.warn('[VNC Auth] VNC Auth not available, using None');
        this.toServer.push(Buffer.from([1]));
        // For None, we go straight to authenticated
        // Forward security types to browser with None selected
        this.toBrowser.push(Buffer.from([1, 1])); // 1 type, type 1 (None)
        this.state = RfbState.AUTHENTICATED;
        return 1 + numTypes;
      }
      console.error('[VNC Auth] VNC Auth (type 2) not available');
      this.state = RfbState.FAILED;
      return 1 + numTypes;
    }
    
    // Select VNC Auth
    this.toServer.push(Buffer.from([2]));
    this.state = RfbState.WAIT_CHALLENGE;
    return 1 + numTypes;
  }
  
  private handleChallenge(): number {
    // Challenge is 16 bytes
    if (this.buffer.length < 16) return 0;
    
    const challenge = this.buffer.subarray(0, 16);
    console.log('[VNC Auth] Received challenge, sending response');
    
    // Encrypt challenge with password
    const response = encryptVncChallenge(challenge, this.password);
    this.toServer.push(response);
    
    this.state = RfbState.WAIT_AUTH_RESULT;
    return 16;
  }
  
  private handleAuthResult(): number {
    // Result is 4 bytes (0 = OK, 1 = failed)
    if (this.buffer.length < 4) return 0;
    
    const result = this.buffer.readUInt32BE(0);
    
    if (result === 0) {
      console.log('[VNC Auth] Authentication successful');
      // Forward security types to browser showing None auth (already authenticated)
      // Browser sees: 1 security type, type 1 (None)
      // Security result is sent AFTER browser responds with its selection (RFB 3.8 requirement)
      this.toBrowser.push(Buffer.from([1, 1])); // 1 type, type 1 (None)
      this.state = RfbState.AUTHENTICATED;
    } else {
      console.error('[VNC Auth] Authentication failed');
      // Read error message if present (RFB 3.8+)
      if (this.buffer.length >= 8) {
        const errorLen = this.buffer.readUInt32BE(4);
        if (this.buffer.length >= 8 + errorLen) {
          const errorMsg = this.buffer.subarray(8, 8 + errorLen).toString('utf-8');
          console.error(`[VNC Auth] Error: ${errorMsg}`);
        }
      }
      this.state = RfbState.FAILED;
    }
    
    return 4;
  }
}
