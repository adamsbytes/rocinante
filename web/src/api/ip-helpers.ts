/**
 * IP Address Helpers with Trusted Proxy Validation
 *
 * SECURITY: Validates X-Forwarded-For header to prevent IP spoofing.
 * Only trusts the header if the direct connection comes from a trusted proxy.
 */

// Trusted proxy CIDR ranges - Cloudflare IPs + localhost for dev
// Source: https://www.cloudflare.com/ips/
const TRUSTED_PROXIES: string[] = [
  // Localhost (development)
  '127.0.0.1/32',
  '::1/128',
  // Cloudflare IPv4
  '173.245.48.0/20',
  '103.21.244.0/22',
  '103.22.200.0/22',
  '103.31.4.0/22',
  '141.101.64.0/18',
  '108.162.192.0/18',
  '190.93.240.0/20',
  '188.114.96.0/20',
  '197.234.240.0/22',
  '198.41.128.0/17',
  '162.158.0.0/15',
  '104.16.0.0/13',
  '104.24.0.0/14',
  '172.64.0.0/13',
  '131.0.72.0/22',
  // Cloudflare IPv6
  '2400:cb00::/32',
  '2606:4700::/32',
  '2803:f800::/32',
  '2405:b500::/32',
  '2405:8100::/32',
  '2a06:98c0::/29',
  '2c0f:f248::/32',
];

/**
 * Check if an IPv4 address is within a CIDR range
 */
function isIpInCidr(ip: string, cidr: string): boolean {
  const [range, bits] = cidr.split('/');
  if (!range || !bits) return false;

  const mask = ~(2 ** (32 - parseInt(bits, 10)) - 1);

  const ipParts = ip.split('.').map(Number);
  const rangeParts = range.split('.').map(Number);

  if (ipParts.length !== 4 || rangeParts.length !== 4) return false;
  if (ipParts.some((p) => isNaN(p) || p < 0 || p > 255)) return false;
  if (rangeParts.some((p) => isNaN(p) || p < 0 || p > 255)) return false;

  const ipInt = (ipParts[0]! << 24) | (ipParts[1]! << 16) | (ipParts[2]! << 8) | ipParts[3]!;
  const rangeInt = (rangeParts[0]! << 24) | (rangeParts[1]! << 16) | (rangeParts[2]! << 8) | rangeParts[3]!;

  return (ipInt & mask) === (rangeInt & mask);
}

/**
 * Check if an IP is in the trusted proxy list
 */
function isTrustedProxy(ip: string): boolean {
  // Handle IPv6
  if (ip.includes(':')) {
    return TRUSTED_PROXIES.some((range) => {
      if (!range.includes(':')) return false;
      const [prefix] = range.split('/');
      // Simple prefix match for IPv6
      return ip.startsWith(prefix!.split(':').slice(0, 4).join(':'));
    });
  }

  // Handle IPv4
  return TRUSTED_PROXIES.some((range) => {
    if (range.includes(':')) return false;
    try {
      return isIpInCidr(ip, range);
    } catch {
      return false;
    }
  });
}

/**
 * Check if an IP address is a private/internal address
 */
function isPrivateIp(ip: string): boolean {
  if (ip.includes(':')) return false; // Skip IPv6 for now

  const parts = ip.split('.').map(Number);
  if (parts.length !== 4 || parts.some((p) => isNaN(p) || p < 0 || p > 255)) {
    return false;
  }

  // 10.0.0.0/8
  if (parts[0] === 10) return true;
  // 172.16.0.0/12
  if (parts[0] === 172 && parts[1]! >= 16 && parts[1]! <= 31) return true;
  // 192.168.0.0/16
  if (parts[0] === 192 && parts[1] === 168) return true;
  // 127.0.0.0/8 (loopback)
  if (parts[0] === 127) return true;

  return false;
}

/**
 * Validate if an IP address is well-formed
 */
function isValidIp(ip: string): boolean {
  const ipv4Regex = /^(\d{1,3}\.){3}\d{1,3}$/;
  const ipv6Regex = /^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$/;

  if (ipv4Regex.test(ip)) {
    const parts = ip.split('.').map(Number);
    return parts.every((part) => part >= 0 && part <= 255);
  }

  return ipv6Regex.test(ip);
}

/**
 * Extract client IP address from request headers with security validation
 *
 * SECURITY: Properly validates the proxy chain to prevent IP spoofing.
 * The X-Forwarded-For header structure is: client, proxy1, proxy2, ..., lastProxy
 *
 * Attack scenario this prevents:
 * 1. Attacker sends: X-Forwarded-For: 1.2.3.4, attacker-ip
 * 2. Cloudflare appends: X-Forwarded-For: 1.2.3.4, attacker-ip, cloudflare-ip
 * 3. WRONG approach: Trust first IP → Returns spoofed 1.2.3.4
 * 4. CORRECT approach: Validate last IP is trusted, then extract rightmost public IP
 */
export function getClientIp(request: Request): string | null {
  // Try Cloudflare-specific header first (most reliable when using Cloudflare)
  const cfConnectingIp = request.headers.get('cf-connecting-ip');
  if (cfConnectingIp && isValidIp(cfConnectingIp)) {
    return cfConnectingIp;
  }

  // Parse X-Forwarded-For header
  const forwardedFor = request.headers.get('x-forwarded-for');
  if (forwardedFor) {
    const ips = forwardedFor.split(',').map((ip) => ip.trim());

    // CRITICAL SECURITY CHECK: Validate the last IP (direct connection) is trusted
    const directConnectionIp = ips[ips.length - 1];

    if (!directConnectionIp || !isTrustedProxy(directConnectionIp)) {
      // Header came from untrusted source - ignore it completely
      // This prevents attackers from injecting fake IPs
      return null;
    }

    // Proxy is trusted - find the rightmost public, non-proxy IP (actual client)
    for (let i = ips.length - 2; i >= 0; i--) {
      const ip = ips[i];
      if (ip && isValidIp(ip) && !isPrivateIp(ip) && !isTrustedProxy(ip)) {
        return ip;
      }
    }

    // All IPs are either private or trusted proxies - return first IP if valid
    const firstIp = ips[0];
    if (firstIp && isValidIp(firstIp) && !isTrustedProxy(firstIp)) {
      return firstIp;
    }

    return null;
  }

  // Try X-Real-IP header (used by some proxies)
  const realIp = request.headers.get('x-real-ip');
  if (realIp && isValidIp(realIp)) {
    return realIp;
  }

  return null;
}

/**
 * Get client IP for rate limiting (with Bun server fallback)
 * Falls back to direct connection IP if no proxy headers
 */
export function getClientIpForRateLimit(
  request: Request,
  server: { requestIP: (req: Request) => { address: string } | null }
): string {
  const proxyIp = getClientIp(request);
  if (proxyIp) {
    return proxyIp;
  }

  // Fallback to direct connection
  return server.requestIP(request)?.address || 'unknown';
}
