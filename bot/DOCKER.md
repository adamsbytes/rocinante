# Docker Deployment Guide

> **Note**: All commands should be run from the project root directory (not from `bot/`).

## Quick Start

```bash
# 1. Copy environment template and configure your accounts
cp .env.example .env
nano .env  # Edit with your account details and proxy configuration

# 2. Build the Docker image (uses bot/Dockerfile)
docker-compose build

# 3. Create directories for persistent data
mkdir -p logs/{account1,account2,account3}
mkdir -p settings/{account1,account2,account3}

# 4. Start all accounts
docker-compose up -d

# 5. View logs to verify startup
docker-compose logs -f
```

## Pre-configured RuneLite Plugins

The Docker image comes with these RuneLite plugins pre-configured:

### Quest Helper (Plugin Hub)
Automatically installed from Plugin Hub on first launch. Provides step-by-step quest guidance.

### Screenshot Plugin (Built-in)
Enabled by default to capture important game events:

| Event Type | Enabled | Notes |
|------------|---------|-------|
| Level ups | ✅ | All 99s and milestones captured |
| Pet drops | ✅ | Rare pet acquisitions |
| Deaths | ✅ | Critical for HCIM tracking |
| Valuable drops | ✅ | Threshold: 100k+ GP value |
| Untraded valuable drops | ✅ | Untradeable rares |
| Boss kills | ✅ | Boss KC milestones |
| Quest/Clue rewards | ✅ | Completion screenshots |
| Collection log entries | ✅ | New unique items |
| Combat achievements | ✅ | CA completions |

Screenshots are saved to `~/.runelite/screenshots/` inside the container.

**To access screenshots from host:**
```bash
# Copy screenshots to host
docker cp rocinante_account1:/home/runelite/.runelite/screenshots ./screenshots_account1/

# Or mount as volume in docker-compose.yml:
volumes:
  - ./screenshots/account1:/home/runelite/.runelite/screenshots
```

---

## Managing Accounts

### Start/Stop Individual Accounts

```bash
# Start specific account
docker-compose up -d account1

# Stop specific account
docker-compose stop account1

# Restart account (e.g., after config change)
docker-compose restart account1

# Remove account container (data persists in volumes)
docker-compose rm -f account1
```

### View Logs

```bash
# All accounts
docker-compose logs -f

# Specific account
docker logs -f rocinante_account1

# Last 100 lines only
docker logs --tail 100 rocinante_account1
```

### Execute Commands in Container

```bash
# Open shell in container
docker exec -it rocinante_account1 /bin/bash

# View RuneLite logs inside container
docker exec rocinante_account1 cat /home/runelite/.runelite/logs/client.log
```

## Adding New Accounts

1. **Edit `docker-compose.yml`**:
```yaml
  account4:
    build: .
    container_name: rocinante_account4
    environment:
      - DISPLAY=:99
      - ACCOUNT_USERNAME=${ACCOUNT4_USER}
      - ACCOUNT_PASSWORD=${ACCOUNT4_PASS}
      - PROXY_HOST=${ACCOUNT4_PROXY_HOST:-}
      - PROXY_PORT=${ACCOUNT4_PROXY_PORT:-}
      - IRONMAN_MODE=false
      - CLAUDE_API_KEY=${CLAUDE_API_KEY}
    volumes:
      - ./profiles/account4:/home/runelite/.runelite/rocinante/profiles
      - ./logs/account4:/home/runelite/.runelite/logs
      - ./settings/account4:/home/runelite/.runelite/settings
    networks:
      account4_net:
        ipv4_address: 172.20.3.10
    restart: unless-stopped
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 2G

networks:
  account4_net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.3.0/24
```

2. **Add to `.env`**:
```bash
ACCOUNT4_USER=your_username
ACCOUNT4_PASS=your_password
ACCOUNT4_PROXY_HOST=proxy4.example.com
ACCOUNT4_PROXY_PORT=8080
```

3. **Create directories**:
```bash
mkdir -p profiles/account4 logs/account4 settings/account4
```

4. **Start new account**:
```bash
docker-compose up -d account4
```

## Proxy Configuration

### HTTP/HTTPS Proxy
Already configured via environment variables. Just set in `.env`:
```bash
ACCOUNT1_PROXY_HOST=proxy.example.com
ACCOUNT1_PROXY_PORT=8080
```

### Authenticated Proxy
If your proxy requires authentication:
```bash
ACCOUNT1_PROXY_USER=proxyuser
ACCOUNT1_PROXY_PASS=proxypassword
```

### SOCKS Proxy
To use SOCKS proxy instead of HTTP proxy, modify `entrypoint.sh`:
```bash
# Add to JVM_ARGS in entrypoint.sh
JVM_ARGS="$JVM_ARGS -DsocksProxyHost=$PROXY_HOST"
JVM_ARGS="$JVM_ARGS -DsocksProxyPort=$PROXY_PORT"
```

## Remote Debugging with VNC

To view the virtual display remotely:

1. **Enable VNC in docker-compose.yml**:
```yaml
environment:
  - ENABLE_VNC=true
ports:
  - "5900:5900"  # Expose VNC port
```

2. **Rebuild and restart**:
```bash
docker-compose up -d --build account1
```

3. **Connect with VNC client**:
- Host: `localhost:5900` (or server IP)
- No password required (for security, use SSH tunnel in production)

## Production Deployment

### Using Docker Secrets (Recommended)

Instead of `.env` file, use Docker secrets for credentials:

```bash
# Create secrets
echo "your_username" | docker secret create account1_user -
echo "your_password" | docker secret create account1_pass -

# Modify docker-compose.yml to use secrets
secrets:
  account1_user:
    external: true
  account1_pass:
    external: true
```

### Systemd Integration

Auto-start on server boot:

```bash
# Create systemd service
sudo nano /etc/systemd/system/rocinante.service
```

```ini
[Unit]
Description=Rocinante RuneLite Automation
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/path/to/rsbot
ExecStart=/usr/bin/docker-compose up -d
ExecStop=/usr/bin/docker-compose down

[Install]
WantedBy=multi-user.target
```

```bash
# Enable and start
sudo systemctl enable rocinante
sudo systemctl start rocinante
```

### Backup Strategy

**Critical Data to Backup**:
1. `./profiles/` - Behavioral profiles (unique per account, cannot be regenerated)
2. `.env` - Account credentials and configuration
3. `./settings/` - RuneLite settings (optional but recommended)

**Automated Backup Script**:
```bash
#!/bin/bash
BACKUP_DIR="/backups/rocinante/$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Backup profiles (critical)
tar -czf "$BACKUP_DIR/profiles.tar.gz" profiles/

# Backup env (encrypted)
gpg --encrypt --recipient you@example.com .env > "$BACKUP_DIR/env.gpg"

# Backup settings
tar -czf "$BACKUP_DIR/settings.tar.gz" settings/

echo "Backup complete: $BACKUP_DIR"
```

## Troubleshooting

### Container Won't Start

```bash
# Check container logs
docker-compose logs account1

# Check Xvfb is running
docker exec rocinante_account1 ps aux | grep Xvfb

# Verify DISPLAY variable
docker exec rocinante_account1 env | grep DISPLAY
```

### RuneLite Plugin Not Loading

```bash
# Verify plugin JAR is copied to container
docker exec rocinante_account1 ls -la /home/runelite/.runelite/plugins/

# Check RuneLite logs
docker exec rocinante_account1 cat /home/runelite/.runelite/logs/client.log
```

### Proxy Not Working

```bash
# Test proxy from inside container
docker exec rocinante_account1 curl -x http://proxy.example.com:8080 https://google.com

# Verify JVM proxy args
docker logs rocinante_account1 2>&1 | grep "proxy"
```

### High Resource Usage

```bash
# Check container resource usage
docker stats

# Adjust limits in docker-compose.yml
deploy:
  resources:
    limits:
      cpus: '0.5'    # Reduce to 0.5 cores
      memory: 1G     # Reduce to 1GB
```

## Monitoring

### Prometheus Metrics (Optional)

Install cAdvisor for container metrics:
```bash
docker run -d \
  --volume=/:/rootfs:ro \
  --volume=/var/run:/var/run:ro \
  --volume=/sys:/sys:ro \
  --volume=/var/lib/docker/:/var/lib/docker:ro \
  --publish=8080:8080 \
  --name=cadvisor \
  google/cadvisor:latest
```

Access metrics at `http://localhost:8080/containers/`

### Log Aggregation

Use Docker logging driver for centralized logs:
```yaml
# In docker-compose.yml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "3"
```

## Security Considerations

1. **Never commit `.env`** - Contains credentials
2. **Use Docker secrets** in production instead of environment variables
3. **Restrict network access** - Only expose necessary ports
4. **Run as non-root** - Already configured in Dockerfile
5. **Keep images updated** - Rebuild regularly for security patches
6. **Encrypt backups** - Behavioral profiles are valuable
7. **Use VPN + proxy** - For maximum anonymity

## Performance Optimization

### Resource Allocation

For 10 accounts on single server:
- **Minimum**: 8 CPU cores, 24GB RAM
- **Recommended**: 12 CPU cores, 32GB RAM
- **Storage**: 100GB SSD for cache and logs

### Network Optimization

```yaml
# Add to docker-compose.yml for better network performance
network_mode: "host"  # Use host network (disables proxy isolation)
```

## Support

For issues:
1. Check container logs: `docker-compose logs`
2. Verify configuration: `docker-compose config`
3. Test proxy: Use curl inside container
4. Check RuneLite logs: `/home/runelite/.runelite/logs/client.log`

