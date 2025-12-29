import { type Component, onMount, onCleanup, createSignal, createEffect, Show } from 'solid-js';

export type VncStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

interface VncViewerProps {
  botId: string;
  class?: string;
  /** Signal accessor indicating whether reconnection should be attempted (e.g., bot is running) */
  shouldConnect?: () => boolean;
  onStatusChange?: (status: VncStatus, error?: string | null) => void;
}

// Basic RFB protocol constants
const RFB_PROTOCOL_VERSION = 'RFB 003.008\n';

// Reconnection constants
const INITIAL_RECONNECT_DELAY = 1000; // 1 second
const MAX_RECONNECT_DELAY = 30000; // 30 seconds

export const VncViewer: Component<VncViewerProps> = (props) => {
  let canvasRef: HTMLCanvasElement | undefined;
  let ws: WebSocket | null = null;
  let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
  
  const [status, setStatus] = createSignal<VncStatus>('connecting');
  const [error, setError] = createSignal<string | null>(null);
  // Server-reported dimensions (actual VNC framebuffer size)
  const [serverDimensions, setServerDimensions] = createSignal({ width: 1920, height: 1080 });
  // Canvas dimensions (fixed at 1080p for consistent zoom)
  const canvasDimensions = { width: 1920, height: 1080 };
  const [reconnectAttempt, setReconnectAttempt] = createSignal(0);
  const [isReconnecting, setIsReconnecting] = createSignal(false);

  // RFB state machine
  let rfbState: 'protocol' | 'security' | 'auth' | 'securityResult' | 'serverInit' | 'normal' = 'protocol';
  let serverName = '';
  let pixelFormat = {
    bitsPerPixel: 32,
    depth: 24,
    bigEndian: false,
    trueColor: true,
    redMax: 255,
    greenMax: 255,
    blueMax: 255,
    redShift: 16,
    greenShift: 8,
    blueShift: 0,
  };

  // Buffer for incoming data
  let dataBuffer = new Uint8Array(0);

  const appendData = (newData: Uint8Array) => {
    const combined = new Uint8Array(dataBuffer.length + newData.length);
    combined.set(dataBuffer);
    combined.set(newData, dataBuffer.length);
    dataBuffer = combined;
  };

  const consumeData = (length: number): Uint8Array | null => {
    if (dataBuffer.length < length) return null;
    const result = dataBuffer.slice(0, length);
    dataBuffer = dataBuffer.slice(length);
    return result;
  };

  const readU8 = (): number | null => {
    const data = consumeData(1);
    return data ? data[0] : null;
  };

  const readU16 = (): number | null => {
    const data = consumeData(2);
    return data ? (data[0] << 8) | data[1] : null;
  };

  const readU32 = (): number | null => {
    const data = consumeData(4);
    return data ? (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3] : null;
  };

  const sendFramebufferUpdateRequest = (incremental: boolean) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    
    // Request the actual server framebuffer size (not our scaled canvas size)
    const { width, height } = serverDimensions();
    const msg = new Uint8Array(10);
    msg[0] = 3; // FramebufferUpdateRequest
    msg[1] = incremental ? 1 : 0;
    msg[2] = 0; msg[3] = 0; // x
    msg[4] = 0; msg[5] = 0; // y
    msg[6] = (width >> 8) & 0xff; msg[7] = width & 0xff;
    msg[8] = (height >> 8) & 0xff; msg[9] = height & 0xff;
    ws.send(msg);
  };

  const processProtocol = () => {
    if (dataBuffer.length < 12) return;
    
    const versionData = consumeData(12);
    if (!versionData) return;
    const version = new TextDecoder().decode(versionData);
    console.log('VNC Server version:', version.trim());
    
    // Send our version
    ws?.send(new TextEncoder().encode(RFB_PROTOCOL_VERSION));
    rfbState = 'security';
  };

  const processSecurity = () => {
    const numTypes = readU8();
    if (numTypes === null) return;
    
    if (numTypes === 0) {
      // Error
      const reasonLen = readU32();
      if (reasonLen === null) { dataBuffer = new Uint8Array([numTypes, ...dataBuffer]); return; }
      const reason = consumeData(reasonLen);
      if (!reason) { dataBuffer = new Uint8Array([numTypes, ...dataBuffer]); return; }
      setError(new TextDecoder().decode(reason));
      setStatus('error');
      return;
    }

    const types = consumeData(numTypes);
    if (!types) { dataBuffer = new Uint8Array([numTypes, ...dataBuffer]); return; }
    
    console.log('Security types:', Array.from(types));
    
    // Choose None (1) or VNC Auth (2)
    let chosen = 1; // None
    if (!types.includes(1) && types.includes(2)) {
      chosen = 2; // VNC Auth
    }
    
    ws?.send(new Uint8Array([chosen]));
    
    if (chosen === 1) {
      rfbState = 'securityResult';
    } else {
      rfbState = 'auth';
    }
  };

  const processAuth = () => {
    // VNC authentication - we need 16 bytes challenge
    const challenge = consumeData(16);
    if (!challenge) return;
    
    // For now, send empty DES-encrypted response (no password)
    // This won't work with password-protected VNC, but our server uses -nopw
    const response = new Uint8Array(16);
    ws?.send(response);
    rfbState = 'securityResult';
  };

  const processSecurityResult = () => {
    // SecurityResult (4 bytes)
    const result = readU32();
    if (result === null) return;
    
    console.log('SecurityResult:', result);
    
    if (result !== 0) {
      setError('Authentication failed');
      setStatus('error');
      return;
    }
    
    // Send ClientInit (share flag)
    ws?.send(new Uint8Array([1])); // Share desktop
    rfbState = 'serverInit';
  };

  const processServerInit = () => {
    // Read ServerInit
    const width = readU16();
    if (width === null) return;
    const height = readU16();
    if (height === null) return;
    
    // Pixel format (16 bytes)
    const pf = consumeData(16);
    if (!pf) return;
    
    // Name length and name
    const nameLen = readU32();
    if (nameLen === null) return;
    const name = consumeData(nameLen);
    if (!name) return;
    
    serverName = new TextDecoder().decode(name);
    console.log('VNC Desktop:', serverName, width, 'x', height);
    
    // Store server dimensions (actual VNC framebuffer size)
    setServerDimensions({ width, height });
    console.log('VNC: Server is', width, 'x', height, '- canvas fixed at', canvasDimensions.width, 'x', canvasDimensions.height);
    
    // Set pixel format to 32-bit RGBA
    const setPixelFormat = new Uint8Array(20);
    setPixelFormat[0] = 0; // SetPixelFormat
    setPixelFormat[4] = 32; // bpp
    setPixelFormat[5] = 24; // depth
    setPixelFormat[6] = 0;  // big-endian
    setPixelFormat[7] = 1;  // true-color
    setPixelFormat[8] = 0; setPixelFormat[9] = 255; // red-max
    setPixelFormat[10] = 0; setPixelFormat[11] = 255; // green-max
    setPixelFormat[12] = 0; setPixelFormat[13] = 255; // blue-max
    setPixelFormat[14] = 16; // red-shift
    setPixelFormat[15] = 8;  // green-shift
    setPixelFormat[16] = 0;  // blue-shift
    ws?.send(setPixelFormat);
    
    // Set encodings (Raw only for simplicity)
    const setEncodings = new Uint8Array(8);
    setEncodings[0] = 2; // SetEncodings
    setEncodings[2] = 0; setEncodings[3] = 1; // 1 encoding
    setEncodings[4] = 0; setEncodings[5] = 0; setEncodings[6] = 0; setEncodings[7] = 0; // Raw
    ws?.send(setEncodings);
    
    rfbState = 'normal';
    setStatus('connected');
    
    // Request initial framebuffer
    sendFramebufferUpdateRequest(false);
  };

  const processNormal = () => {
    // Save buffer state for potential rollback
    const savedBuffer = dataBuffer.slice();
    
    const msgType = readU8();
    if (msgType === null) return;
    
    if (msgType === 0) {
      // FramebufferUpdate
      const padding = consumeData(1);
      if (!padding) { dataBuffer = savedBuffer; return; }
      
      const numRects = readU16();
      if (numRects === null) { dataBuffer = savedBuffer; return; }
      
      for (let i = 0; i < numRects; i++) {
        const x = readU16();
        const y = readU16();
        const w = readU16();
        const h = readU16();
        const encoding = readU32();
        
        if (x === null || y === null || w === null || h === null || encoding === null) {
          dataBuffer = savedBuffer;
          return;
        }
        
        if (encoding === 0) {
          // Raw encoding
          const pixelDataLen = w * h * 4;
          
          // Skip empty rectangles
          if (w === 0 || h === 0) continue;
          
          const pixelData = consumeData(pixelDataLen);
          if (!pixelData) {
            dataBuffer = savedBuffer;
            return;
          }
          
          // Draw to canvas with scaling
          if (canvasRef) {
            const ctx = canvasRef.getContext('2d');
            if (ctx) {
              // Calculate scale factor (server -> canvas)
              const server = serverDimensions();
              const scaleX = canvasDimensions.width / server.width;
              const scaleY = canvasDimensions.height / server.height;
              
              // Create temporary canvas for the incoming rectangle
              const tempCanvas = document.createElement('canvas');
              tempCanvas.width = w;
              tempCanvas.height = h;
              const tempCtx = tempCanvas.getContext('2d');
              if (tempCtx) {
                const imageData = tempCtx.createImageData(w, h);
              for (let j = 0; j < w * h; j++) {
                // VNC sends BGRX, canvas wants RGBA
                imageData.data[j * 4 + 0] = pixelData[j * 4 + 2]; // R
                imageData.data[j * 4 + 1] = pixelData[j * 4 + 1]; // G
                imageData.data[j * 4 + 2] = pixelData[j * 4 + 0]; // B
                imageData.data[j * 4 + 3] = 255; // A
              }
                tempCtx.putImageData(imageData, 0, 0);
                
                // Draw scaled to main canvas
                ctx.drawImage(
                  tempCanvas,
                  0, 0, w, h,  // source rect
                  x * scaleX, y * scaleY, w * scaleX, h * scaleY  // dest rect (scaled)
                );
              }
            }
          }
        }
      }
      
      // Request next update
      setTimeout(() => sendFramebufferUpdateRequest(true), 50);
    } else if (msgType === 1) {
      // SetColorMapEntries - skip
      consumeData(1); // padding
      const firstColor = readU16();
      const numColors = readU16();
      if (firstColor === null || numColors === null) return;
      consumeData(numColors * 6);
    } else if (msgType === 2) {
      // Bell - ignore
    } else if (msgType === 3) {
      // ServerCutText
      consumeData(3); // padding
      const len = readU32();
      if (len === null) return;
      consumeData(len);
    }
  };

  const processData = () => {
    try {
      while (dataBuffer.length > 0) {
        const prevLen = dataBuffer.length;
        
        switch (rfbState) {
          case 'protocol': processProtocol(); break;
          case 'security': processSecurity(); break;
          case 'auth': processAuth(); break;
          case 'securityResult': processSecurityResult(); break;
          case 'serverInit': processServerInit(); break;
          case 'normal': processNormal(); break;
        }
        
        // If no data was consumed, wait for more
        if (dataBuffer.length === prevLen) break;
      }
    } catch (err) {
      console.error('RFB processing error:', err);
    }
  };

  /** Calculate reconnection delay with exponential backoff */
  const getReconnectDelay = (attempt: number): number => {
    return Math.min(INITIAL_RECONNECT_DELAY * Math.pow(2, attempt), MAX_RECONNECT_DELAY);
  };

  /** Schedule a reconnection attempt with exponential backoff */
  const scheduleReconnect = () => {
    // Only reconnect if shouldConnect returns true (or if prop not provided, always reconnect)
    const shouldReconnect = props.shouldConnect?.() ?? true;
    if (!shouldReconnect) {
      console.log('VNC: Not reconnecting - shouldConnect is false');
      setIsReconnecting(false);
      return;
    }

    // Clear any existing reconnect timeout
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }

    const attempt = reconnectAttempt();
    const delay = getReconnectDelay(attempt);
    
    console.log(`VNC: Scheduling reconnect attempt ${attempt + 1} in ${delay}ms`);
    
    setIsReconnecting(true);
    setStatus('connecting');
    
    reconnectTimeoutId = setTimeout(() => {
      reconnectTimeoutId = null;
      setReconnectAttempt(a => a + 1);
      connect();
    }, delay);
  };

  /** Establish WebSocket connection to VNC proxy */
  const connect = () => {
    // Close existing connection if any
    if (ws) {
      ws.onclose = null; // Prevent triggering reconnect on intentional close
      ws.onerror = null;
      ws.close();
      ws = null;
    }

    // Build WebSocket URL for VNC proxy
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/vnc/${props.botId}`;

    try {
      setStatus('connecting');
      setError(null);
      rfbState = 'protocol';
      dataBuffer = new Uint8Array(0);

      ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        console.log('VNC: WebSocket connected');
        // Reset reconnection state on successful connection
        setReconnectAttempt(0);
        setIsReconnecting(false);
      };

      ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
          appendData(new Uint8Array(event.data));
          processData();
        }
      };

      ws.onclose = (event) => {
        console.log('VNC: WebSocket closed:', event.code, event.reason);
        setStatus('disconnected');
        
        // Schedule reconnection if appropriate
        scheduleReconnect();
      };

      ws.onerror = (event) => {
        console.error('VNC: WebSocket error:', event);
        // Don't set error state here - let onclose handle the reconnection
        // Only set error if this is a persistent failure
        if (reconnectAttempt() > 0) {
          setError('Connection error');
        }
      };

    } catch (err) {
      console.error('VNC: Failed to create WebSocket:', err);
      setStatus('error');
      setError(err instanceof Error ? err.message : 'Failed to connect');
      // Schedule reconnection even on creation failure
      scheduleReconnect();
    }
  };

  onMount(() => {
    connect();
  });

  onCleanup(() => {
    // Clear reconnection timeout
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }
    
    // Close WebSocket
    if (ws) {
      ws.onclose = null; // Prevent triggering reconnect on cleanup
      ws.onerror = null;
      ws.close();
      ws = null;
    }
  });

  // React to shouldConnect changes - if it becomes false, stop reconnecting
  createEffect(() => {
    const shouldConnect = props.shouldConnect?.() ?? true;
    if (!shouldConnect && reconnectTimeoutId) {
      console.log('VNC: Cancelling reconnection - shouldConnect became false');
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
      setIsReconnecting(false);
    }
  });

  // Set canvas size once on mount (fixed at 1080p)
  createEffect(() => {
    if (canvasRef) {
      canvasRef.width = canvasDimensions.width;
      canvasRef.height = canvasDimensions.height;
    }
  });

  // Notify parent of status changes
  createEffect(() => {
    props.onStatusChange?.(status(), error());
  });

  return (
    <div class={`relative bg-black rounded-lg overflow-hidden ${props.class ?? ''}`}>
      <canvas
        ref={canvasRef}
        width={canvasDimensions.width}
        height={canvasDimensions.height}
        class="w-full h-auto"
        style={{ "max-width": "100%", "aspect-ratio": `${canvasDimensions.width} / ${canvasDimensions.height}` }}
      />
      <Show when={status() !== 'connected'}>
        {/* Use reduced opacity (bg-black/60) to keep last frame visible during reconnection */}
        <div class={`absolute inset-0 flex items-center justify-center ${isReconnecting() ? 'bg-black/60' : 'bg-black/80'}`}>
          <div class="text-center">
            <Show when={status() === 'connecting'}>
              <div class="w-8 h-8 border-2 border-[var(--accent)] border-t-transparent rounded-full animate-spin mx-auto mb-2" />
              <Show 
                when={isReconnecting()}
                fallback={<p class="text-[var(--text-secondary)]">Connecting to VNC...</p>}
              >
                <p class="text-amber-400">
                  Reconnecting{reconnectAttempt() > 1 ? ` (attempt ${reconnectAttempt()})` : '...'}
                </p>
              </Show>
            </Show>
            <Show when={status() === 'disconnected' && !isReconnecting()}>
              <p class="text-[var(--text-secondary)]">VNC Disconnected</p>
            </Show>
            <Show when={status() === 'error' && !isReconnecting()}>
              <p class="text-red-400">{error() || 'Connection failed'}</p>
            </Show>
          </div>
        </div>
      </Show>
    </div>
  );
};
