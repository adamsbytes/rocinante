/**
 * Client-Side Device Fingerprinting
 *
 * Captures browser device characteristics for magic link security validation.
 * These signals are hard to fake and persist across network changes.
 */

export interface ClientFingerprintData {
  viewportWidth: number;
  viewportHeight: number;
  devicePixelRatio: number;
  connectionType: string | null;
  timezone: string;
  timezoneOffset: number;
  hardwareConcurrency: number | null;
  deviceMemory: number | null;
  doNotTrack: string | null;
  cookieEnabled: boolean;
}

/**
 * Capture device fingerprint from browser APIs
 */
export function captureClientFingerprint(): ClientFingerprintData {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const devicePixelRatio = window.devicePixelRatio || 1;

  // Network connection type (not available in all browsers)
  const connection =
    (navigator as any).connection ||
    (navigator as any).mozConnection ||
    (navigator as any).webkitConnection;
  const connectionType = connection?.effectiveType || connection?.type || null;

  // Timezone
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const timezoneOffset = new Date().getTimezoneOffset();

  // Hardware capabilities
  const hardwareConcurrency = navigator.hardwareConcurrency || null;
  const deviceMemory = (navigator as any).deviceMemory || null;

  // Privacy settings
  const doNotTrack =
    navigator.doNotTrack || (window as any).doNotTrack || (navigator as any).msDoNotTrack || null;
  const cookieEnabled = navigator.cookieEnabled;

  return {
    viewportWidth,
    viewportHeight,
    devicePixelRatio,
    connectionType,
    timezone,
    timezoneOffset,
    hardwareConcurrency,
    deviceMemory,
    doNotTrack,
    cookieEnabled,
  };
}

/**
 * Encode fingerprint for transmission in HTTP header
 */
export function encodeFingerprint(fingerprint: ClientFingerprintData): string {
  return btoa(JSON.stringify(fingerprint));
}

/**
 * Capture and encode fingerprint in one call
 */
export function captureEncodedFingerprint(): string {
  return encodeFingerprint(captureClientFingerprint());
}
